#!/usr/bin/env bash
# ============================================================================
# 状态与资源观测
#
# 回答四个问题：
#   1. 容器内存上限**实际生效值**是多少？（不是看 compose 文件，而是看 docker inspect）
#   2. 现在真实占用了多少？宿主还剩多少？
#   3. 有没有容器被 OOMKilled / 在反复重启？（1 核 2 GB 上最主要的失败模式）
#   4. 哪些端口暴露到了宿主机？绑定地址是不是只有回环？
#
# 用法：
#   ./scripts/server/status.sh             # 看当前状态
#   ./scripts/server/status.sh --record    # 同时追加一行到资源日志（用于阶段对比）
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

RECORD=0
[[ "${1:-}" == "--record" ]] && RECORD=1

require_docker

echo "============================================================"
echo " TrustAgent 状态  $(date -Iseconds)"
echo "============================================================"

# ---------------------------------------------------------------------------
echo
echo "[1] 容器状态"
# ---------------------------------------------------------------------------
printf '  %-28s %-10s %-8s %-14s %s\n' "容器" "状态" "重启次数" "内存上限" "健康"
for svc in mysql redis ai-service ai-worker backend web; do
  name="trustagent-${svc}"
  if ! docker inspect "$name" >/dev/null 2>&1; then
    printf '  %-28s %s\n' "$name" "未创建"
    continue
  fi
  state="$(docker inspect -f '{{.State.Status}}' "$name")"
  restarts="$(docker inspect -f '{{.RestartCount}}' "$name")"
  # 这里是关键：读的是容器**实际生效**的上限，不是 compose 文件里写的值。
  # 两者不一致（例如变量没被替换、或有人用 -f 指定了别的文件）时，
  # 只有 inspect 能发现。
  memlimit="$(docker inspect -f '{{if .HostConfig.Memory}}{{.HostConfig.Memory}}{{else}}unlimited{{end}}' "$name")"
  if [[ "$memlimit" == "unlimited" ]]; then
    memlimit_txt="无限制"
  else
    memlimit_txt="$(awk -v b="$memlimit" 'BEGIN{printf "%.0f MiB", b/1048576}')"
  fi
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}' "$name")"
  printf '  %-28s %-10s %-8s %-14s %s\n' "$name" "$state" "$restarts" "$memlimit_txt" "$health"
done

# ---------------------------------------------------------------------------
echo
echo "[2] 内存上限是否符合低配预算"
# ---------------------------------------------------------------------------
# 期望值与 docker-compose.lowmem.yml 必须一致。硬编码在这里是有意的：
# 如果 compose 被改动，这里就会报出来，从而强制两边同步。
check_limit() {
  local name="$1" expected_mb="$2"
  if ! docker inspect "$name" >/dev/null 2>&1; then return 0; fi
  local bytes actual_mb
  bytes="$(docker inspect -f '{{.HostConfig.Memory}}' "$name")"
  actual_mb=$(( bytes / 1048576 ))
  if (( actual_mb == expected_mb )); then
    ok "${name}: ${actual_mb} MiB"
  else
    bad "${name}: 实际 ${actual_mb} MiB，期望 ${expected_mb} MiB（compose 与实际不一致）"
  fi
}
check_limit trustagent-backend 512
check_limit trustagent-mysql 512
check_limit trustagent-ai-service 192
check_limit trustagent-ai-worker 160
check_limit trustagent-redis 96
check_limit trustagent-web 64

# ---------------------------------------------------------------------------
echo
echo "[3] 实时资源占用"
# ---------------------------------------------------------------------------
# --no-stream：取一次快照就退出，避免把运维终端卡在滚动输出里
if docker ps --format '{{.Names}}' | grep -q '^trustagent-'; then
  docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' \
    $(docker ps --filter 'name=trustagent-' --format '{{.Names}}') 2>/dev/null | sed 's/^/  /'
else
  warn "没有正在运行的 TrustAgent 容器"
fi

echo
MEM_TOTAL_MB="$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo)"
MEM_AVAIL_MB="$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo)"
SWAP_TOTAL_MB="$(awk '/SwapTotal/ {printf "%d", $2/1024}' /proc/meminfo)"
SWAP_FREE_MB="$(awk '/SwapFree/ {printf "%d", $2/1024}' /proc/meminfo)"
ok "宿主 MemTotal=${MEM_TOTAL_MB} MiB  MemAvailable=${MEM_AVAIL_MB} MiB"
if (( SWAP_TOTAL_MB > 0 )); then
  SWAP_USED=$(( SWAP_TOTAL_MB - SWAP_FREE_MB ))
  if (( SWAP_USED > 50 )); then
    warn "swap 已用 ${SWAP_USED} MiB —— 说明内存压力已经导致换页。"
    echo "         这不是健康状态：换页会把交互延迟拉到秒级。"
    echo "         正确处理是降低 mem_limit 或换更大内存的机器，而不是'有 swap 兜着就行'。"
  else
    ok "swap 总计 ${SWAP_TOTAL_MB} MiB，已用 ${SWAP_USED} MiB（未持续依赖 swap）"
  fi
else
  ok "宿主无 swap：内存超限时容器会被直接 OOMKill，而不是慢慢换页"
fi

if (( MEM_AVAIL_MB < 200 )); then
  bad "MemAvailable 仅 ${MEM_AVAIL_MB} MiB，已处于危险区间。"
  echo "         建议立即停止非必要服务，或降低容器上限。持续低内存运行随时可能 OOM。"
fi

LOAD1="$(awk '{print $1}' /proc/loadavg 2>/dev/null || echo '?')"
ok "CPU 负载(1min)=${LOAD1}  核数=$(nproc)"

# ---------------------------------------------------------------------------
echo
echo "[4] 异常：OOMKilled 与重启"
# ---------------------------------------------------------------------------
# 1 核 2 GB 上最典型的失败模式就是某个容器被 OOMKill 然后重启，
# 表现为"页面偶尔打不开"而日志里看不出原因。这里把它显式标出来。
PROBLEMS=0
for svc in mysql redis ai-service ai-worker backend web; do
  name="trustagent-${svc}"
  docker inspect "$name" >/dev/null 2>&1 || continue
  oom="$(docker inspect -f '{{.State.OOMKilled}}' "$name")"
  restarts="$(docker inspect -f '{{.RestartCount}}' "$name")"
  exitcode="$(docker inspect -f '{{.State.ExitCode}}' "$name")"
  if [[ "$oom" == "true" ]]; then
    bad "${name} 曾被 OOMKilled（内存上限太小或存在内存泄漏）"
    PROBLEMS=$(( PROBLEMS + 1 ))
  fi
  if (( restarts >= 3 )); then
    bad "${name} 重启 ${restarts} 次 —— 疑似崩溃循环"
    PROBLEMS=$(( PROBLEMS + 1 ))
  fi
  if [[ "$(docker inspect -f '{{.State.Status}}' "$name")" == "exited" && "$exitcode" != "0" ]]; then
    bad "${name} 已退出，退出码 ${exitcode}"
    PROBLEMS=$(( PROBLEMS + 1 ))
  fi
done
(( PROBLEMS == 0 )) && ok "未发现 OOMKilled / 崩溃循环"

# ---------------------------------------------------------------------------
echo
echo "[5] 端口暴露面（这是本部署最重要的安全属性）"
# ---------------------------------------------------------------------------
# 只检查"容器实际把端口映射到了宿主哪里"，而不去看 UFW/安全组配置。
# 原因：Docker 自己写 iptables 规则，常常绕过 UFW 的 INPUT 链，
# 于是 `ufw status` 显示端口被拒绝、实际依然可达。
# 唯一可信的依据是容器自己的绑定。
EXPOSURE_PROBLEM=0
for svc in mysql redis ai-service ai-worker backend web; do
  name="trustagent-${svc}"
  docker inspect "$name" >/dev/null 2>&1 || continue
  # (. // {}) 兜住 Ports 为 null 的情况，否则 jq 的 to_entries 会直接报错
  published="$(docker inspect -f '{{json .NetworkSettings.Ports}}' "$name" | jq -r '
    (. // {})
    | to_entries
    | map(select(.value != null))
    | map("\(.key) -> \(.value[0].HostIp):\(.value[0].HostPort)")
    | join(", ")')"
  if [[ -z "$published" ]]; then
    ok "${name}: 未在宿主机上监听任何端口"
  else
    # 允许的唯一情形：web 绑在 127.0.0.1
    if [[ "$svc" == "web" ]] && [[ "$published" == *"127.0.0.1:"* ]] && [[ "$published" != *"0.0.0.0:"* ]]; then
      ok "${name}: ${published}（仅回环，符合预期）"
    else
      bad "${name}: ${published} —— 不应对外发布！"
      EXPOSURE_PROBLEM=$(( EXPOSURE_PROBLEM + 1 ))
    fi
  fi
done

# 交叉验证：宿主上是否存在绑到非回环地址的监听。
#
# 这条检查针对的是本项目的核心安全属性：**入口只允许绑回环**。
# 私有模式下的唯一对外端口是 web 的 ${TRUSTAGENT_WEB_PORT:-8090}，它必须绑在 127.0.0.1，
# 只能通过 SSH 隧道访问；内部服务（MySQL/Redis/执行面/控制面）在宿主上不监听任何端口。
# 上面按容器 inspect 的结果是"compose 视角"，这里再按宿主实际监听地址交叉验证一次——
# docker-proxy、手工 docker run -p、或上一版没删干净的容器都会在这里露出来。
#
# 端口列表**从 compose 的实际配置读**，不写死：
#   以前这里硬编码过端口列表，而真实端口是 8090 ——
#   唯一的对外端口恰好是唯一没被检查的那个，这类漂移只能靠"从配置推导"来根治。
if command -v ss >/dev/null 2>&1; then
  # (a) compose 解析后的实际发布端口（已代入 .env 与默认值）
  PUBLISHED_PORTS="$(compose config 2>/dev/null \
    | awk '/^[[:space:]]*published:/ { gsub(/"/, "", $2); if ($2 ~ /^[0-9]+$/) print $2 }' \
    | sort -un | tr '\n' ' ' || true)"
  [[ -n "${PUBLISHED_PORTS// /}" ]] || \
    warn "未能从 docker compose config 解析发布端口（.env 未配全？），下方按 .env 与内置列表检查"
  # (b) 内部服务端口：它们**不应该**出现在宿主的任何监听地址上。
  #     与 (a) 取并集：只看 (a) 时，"忘了删的旧容器"或手工 -p 的容器都不会被发现。
  # 逐个写出而不是拼一行：GREP 正则不能出现空分支（空的端口号会让 ":()$" 匹配任意端口）。
  WATCH_PORTS="$(printf '%s\n' ${PUBLISHED_PORTS:-} \
      "$(get_env TRUSTAGENT_WEB_PORT 8090)" 3306 6379 8000 8080 \
      | grep -E '^[0-9]+$' | sort -un | tr '\n' ' ')"
  WATCH_PORTS="${WATCH_PORTS% }"
  PORT_RE=""
  for p in $WATCH_PORTS; do PORT_RE="${PORT_RE:+$PORT_RE|}$p"; done
  OPEN="$(ss -ltnH 2>/dev/null | awk '{print $4}' | grep -E '^(0\.0\.0\.0|\*|\[::\]):' \
          | grep -E ":(${PORT_RE})$" || true)"
  if [[ -n "$OPEN" ]]; then
    bad "宿主上存在对外监听：$(printf '%s' "$OPEN" | tr '\n' ' ')"
    echo "         入口只允许绑回环（127.0.0.1）是本部署的安全属性："
    echo "         少写 127.0.0.1 前缀 = 把服务暴露到公网，而 compose 文件上看起来只是'映射了一个端口'。"
    EXPOSURE_PROBLEM=$(( EXPOSURE_PROBLEM + 1 ))
  else
    ok "宿主无对外监听（已检查端口：${WATCH_PORTS}）"
  fi
fi
(( EXPOSURE_PROBLEM == 0 )) || echo "         → 请立即检查 docker-compose.lowmem.yml 的 ports 段"

# ---------------------------------------------------------------------------
echo
echo "[6] 评测队列积压"
# ---------------------------------------------------------------------------
# Stream 没有 MAXLEN 裁剪（刻意如此：按条数裁剪会丢未处理任务），
# 所以积压量需要有人盯。这里给出未确认（PEL）与流总长度两个数。
if docker inspect trustagent-redis >/dev/null 2>&1 && \
   [[ "$(docker inspect -f '{{.State.Status}}' trustagent-redis)" == "running" ]]; then
  # 口令在**容器内部**通过环境变量取用，不出现在宿主的进程列表里，
  # 也不会被写进任何日志或脚本的调试输出。
  STREAM_LEN="$(docker exec trustagent-redis sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" XLEN eval.jobs' 2>/dev/null || echo '?')"
  PENDING="$(docker exec trustagent-redis sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" XPENDING eval.jobs cg:eval' 2>/dev/null | head -n 1 || echo '?')"
  DLQ_LEN="$(docker exec trustagent-redis sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" XLEN eval.jobs.dlq' 2>/dev/null || echo '0')"
  ok "eval.jobs 长度=${STREAM_LEN}  未确认(PEL)=${PENDING}  死信=${DLQ_LEN}"
  if [[ "$PENDING" =~ ^[0-9]+$ ]] && (( PENDING > 0 )); then
    warn "有 ${PENDING} 条未确认消息。若长时间不下降，说明 Worker 卡住或已停止。"
    echo "         查看：docker compose -f docker-compose.lowmem.yml logs --tail=60 ai-worker"
  fi
  if [[ "$DLQ_LEN" =~ ^[0-9]+$ ]] && (( DLQ_LEN > 0 )); then
    warn "死信队列有 ${DLQ_LEN} 条：这些任务重试耗尽后失败，需要人工介入。"
  fi
  MEMUSED="$(docker exec trustagent-redis sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" INFO memory' 2>/dev/null | grep -E '^used_memory_human' | tr -d '\r' || true)"
  [[ -n "$MEMUSED" ]] && ok "Redis ${MEMUSED}（上限 64 MiB，策略 noeviction）"
else
  warn "Redis 未运行，跳过队列检查"
fi

# ---------------------------------------------------------------------------
echo
echo "[7] 磁盘"
# ---------------------------------------------------------------------------
df -h "$PROJECT_ROOT" 2>/dev/null | sed 's/^/  /' || true
DOCKER_ROOT="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || echo '')"
if [[ -n "$DOCKER_ROOT" ]]; then
  docker system df 2>/dev/null | sed 's/^/  /' || true
fi

# ---------------------------------------------------------------------------
if (( RECORD == 1 )); then
  LOGFILE="$STATE_DIR/resource-log.csv"
  if [[ ! -f "$LOGFILE" ]]; then
    echo "timestamp,mem_total_mb,mem_avail_mb,swap_used_mb,load1,container_mem_mb,oom_events,restarts" > "$LOGFILE"
  fi
  CONT_MEM="$(docker stats --no-stream --format '{{.MemUsage}}' \
    $(docker ps --filter 'name=trustagent-' --format '{{.Names}}') 2>/dev/null \
    | awk -F'/' '{gsub(/[^0-9.]/,"",$1); s+=$1} END{printf "%.1f", s}')"
  OOM_COUNT=0; RESTART_SUM=0
  for svc in mysql redis ai-service ai-worker backend web; do
    n="trustagent-${svc}"
    docker inspect "$n" >/dev/null 2>&1 || continue
    [[ "$(docker inspect -f '{{.State.OOMKilled}}' "$n")" == "true" ]] && OOM_COUNT=$(( OOM_COUNT + 1 ))
    RESTART_SUM=$(( RESTART_SUM + $(docker inspect -f '{{.RestartCount}}' "$n") ))
  done
  echo "$(date -Iseconds),${MEM_TOTAL_MB},${MEM_AVAIL_MB},$(( SWAP_TOTAL_MB - SWAP_FREE_MB )),${LOAD1},${CONT_MEM:-0},${OOM_COUNT},${RESTART_SUM}" >> "$LOGFILE"
  echo
  ok "已记录到 ${LOGFILE#$PROJECT_ROOT/}"
fi

echo
echo "============================================================"

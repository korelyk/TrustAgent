#!/usr/bin/env bash
# ============================================================================
# 真实 MySQL + 真实 Redis Streams 验证（H2 / 内存实现覆盖不到的部分）
#
# 为什么必须有这个脚本：
#   本机测试跑在 H2(MODE=MySQL) + 内存限流/队列上。它能证明业务逻辑对，
#   但证明不了这些只能在真实组件上暴露的问题：
#     * Flyway 脚本在真实 MySQL 8 上能否从空库跑通（方言、索引长度、排序规则）
#     * 唯一约束是否真的拦住重复写入（H2 与 MySQL 的约束行为并不完全一致）
#     * Redis Streams 的 PEL / XAUTOCLAIM / 投递计数 / 死信在真实 Redis 上的行为
#     * 重启之后数据是否还在
#   所以这里**必须走真实队列路径**，不能用"同步调用也能跑通"来代替。
#
# 安全边界（这些不是保守，是必须）：
#   * 会写库的验证一律放在事务里并回滚，不往审计表留假记录——
#     否则会破坏审计哈希链，让后续的 /api/audit/verify 莫名其妙变红。
#   * 队列机制验证用**独立的探测 stream**（verify.jobs），
#     不干扰正在运行的 Worker 与真实评测队列 eval.jobs。
#   * 不做压测、不并发、不调整系统的 OOM 保护、不自动创建 swap。
#   * 每段之间打印内存快照，便于定位是哪一步把内存打满。
#
# 用法：
#   ./scripts/server/verify-stack.sh              # 结构 + 队列机制 + 访问控制
#   ./scripts/server/verify-stack.sh --full-eval  # 额外跑 100 条 × 双配置评测
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

FULL_EVAL=0
[[ "${1:-}" == "--full-eval" ]] && FULL_EVAL=1

BASE_URL="http://127.0.0.1:$(get_env TRUSTAGENT_WEB_PORT 8090)"
DB="$(get_env TRUSTAGENT_DB_NAME trustagent)"
# 探测用 stream：与 eval.jobs 完全隔离，避免干扰真实队列
PROBE="verify.jobs"
PROBE_DLQ="verify.jobs.dlq"
PROBE_GROUP="cg:verify"

PASS=0; FAIL=0; SKIP=0

for cmd in curl jq; do
  command -v "$cmd" >/dev/null 2>&1 || die "缺少命令 $cmd"
done
require_docker
require_env_file

# ---------------------------------------------------------------------------
# 断言辅助
# ---------------------------------------------------------------------------
res() {  # res <id> <0|1> <描述> <证据>
  local id="$1" ok="$2" desc="$3" ev="$4"
  if [[ "$ok" == "1" ]]; then
    printf '[%s] PASS  %s\n       %s\n' "$id" "$desc" "$ev"
    PASS=$(( PASS + 1 ))
  else
    printf '[%s] FAIL  %s\n       %s\n' "$id" "$desc" "$ev"
    FAIL=$(( FAIL + 1 ))
  fi
}
skip() {  # skip <id> <描述> <原因>
  printf '[%s] SKIP  %s\n       %s\n' "$1" "$2" "$3"
  SKIP=$(( SKIP + 1 ))
}
is_int() { [[ "${1:-}" =~ ^[0-9]+$ ]]; }

mem_snapshot() {
  local host_avail cont
  host_avail="$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo)"
  cont="$(docker stats --no-stream --format '{{.MemUsage}}' \
          $(docker ps --filter 'name=trustagent-' --format '{{.Names}}') 2>/dev/null \
          | awk -F'/' '{gsub(/[^0-9.]/,"",$1); s+=$1} END{printf "%.0f", s}')"
  printf '       [资源] 宿主 MemAvailable=%s MiB   容器合计=%s MiB\n' "${host_avail}" "${cont:-0}"
}

sql() {
  docker exec trustagent-mysql sh -c \
    "exec mysql -N -B -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"$1\"" 2>/dev/null | tr -d '\r'
}
# 执行一条**期望报错**的 SQL；返回 "1" 表示确实失败了
sql_expect_fail() {
  if docker exec trustagent-mysql sh -c \
       "exec mysql -N -B -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"$1\"" >/dev/null 2>&1; then
    echo "0"
  else
    echo "1"
  fi
}
rcli() {
  docker exec trustagent-redis sh -c \
    'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" '"$*" 2>/dev/null | tr -d '\r'
}
api() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}" extra="${5:-}"
  local args=(-sS -X "$method" "${BASE_URL}${path}" -H 'Content-Type: application/json'
              -H "X-Trace-Id: verify-$(date +%s%N)" --max-time 120 -w $'\n%{http_code}')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer ${token}")
  [[ -n "$extra" ]] && args+=(-H "$extra")
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}" 2>&1 || true
}

# 与 api 相同，但请求体从 **stdin** 取（--data @-）：
# 请求体含口令/密钥时不能拼进 argv，宿主 ps 对其他用户可见。
api_stdin() {
  local method="$1" path="$2" token="${3:-}" extra="${4:-}"
  local args=(-sS -X "$method" "${BASE_URL}${path}" -H 'Content-Type: application/json'
              -H "X-Trace-Id: verify-$(date +%s%N)" --max-time 120 --data @- -w $'\n%{http_code}')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer ${token}")
  [[ -n "$extra" ]] && args+=(-H "$extra")
  curl "${args[@]}" 2>&1 || true
}
code_of() { printf '%s' "$1" | tail -n 1; }
body_of() { printf '%s' "$1" | sed '$d'; }
data_of() { body_of "$1" | jq -r '.data' 2>/dev/null; }

echo "============================================================"
echo " TrustAgent 真实组件验证  $(date -Iseconds)"
echo " 数据库=${DB}   完整评测=$([[ $FULL_EVAL == 1 ]] && echo 是 || echo 否)"
echo "============================================================"

for svc in mysql redis backend ai-worker web; do
  docker inspect "trustagent-${svc}" >/dev/null 2>&1 \
    || die "容器 trustagent-${svc} 不存在。请先 ./scripts/server/deploy.sh"
  [[ "$(docker inspect -f '{{.State.Status}}' "trustagent-${svc}")" == "running" ]] \
    || die "容器 trustagent-${svc} 未运行"
done
ok "所需容器均在运行"
echo
mem_snapshot

# ===========================================================================
echo
echo "############ A. 真实 MySQL ############"
echo

echo "--- A1 Flyway 从空库迁移 ---"
FLYWAY_OK="$(sql "SELECT COUNT(*) FROM ${DB}.flyway_schema_history WHERE success=1;")"
FLYWAY_BAD="$(sql "SELECT COUNT(*) FROM ${DB}.flyway_schema_history WHERE success=0;")"
FLYWAY_V="$(sql "SELECT GROUP_CONCAT(version ORDER BY installed_rank SEPARATOR ',') FROM ${DB}.flyway_schema_history WHERE success=1;")"
if is_int "$FLYWAY_OK" && (( FLYWAY_OK >= 2 )) && [[ "$FLYWAY_BAD" == "0" ]]; then
  res A1 1 "Flyway 迁移全部成功" "成功 ${FLYWAY_OK} 条 / 失败 0 条；版本=[${FLYWAY_V}]"
else
  res A1 0 "Flyway 迁移状态异常" "成功=${FLYWAY_OK} 失败=${FLYWAY_BAD} 版本=[${FLYWAY_V}]"
fi

echo "--- A2 唯一约束真的生效（事务内验证，结束回滚）---"
SEQ_PROBE=88888888
# 审计表 uk_audit_log_seq：同一 seq 写两次，第二次必须被拒绝。
# 必须用事务包住：直接写会往审计表留下假记录，破坏哈希链。
UNIQ="$(sql_expect_fail "START TRANSACTION; INSERT INTO ${DB}.audit_log (seq, actor_username, action, record_hash, created_at, created_at_epoch_ms) VALUES (${SEQ_PROBE},'verify','PROBE','deadbeef',NOW(),1); INSERT INTO ${DB}.audit_log (seq, actor_username, action, record_hash, created_at, created_at_epoch_ms) VALUES (${SEQ_PROBE},'verify','PROBE','deadbeef',NOW(),1);")"
LEFT="$(sql "SELECT COUNT(*) FROM ${DB}.audit_log WHERE seq=${SEQ_PROBE};")"
if [[ "$UNIQ" == "1" ]] && [[ "$LEFT" == "0" ]]; then
  res A2 1 "唯一约束生效，且事务回滚后无残留（审计链未被污染）" "重复插入被拒绝；残留行数=0"
elif [[ "$UNIQ" == "1" ]]; then
  res A2 0 "约束生效但留下了残留行" "残留=${LEFT}（需人工清理 seq=${SEQ_PROBE} 并重算链）"
else
  res A2 0 "唯一约束未生效：重复 seq 写入成功" "这会让审计链出现歧义位置"
fi

echo "--- A3 幂等表唯一约束 ---"
IDEM="$(sql_expect_fail "START TRANSACTION; INSERT INTO ${DB}.idempotency_record (idem_key, scope, status, created_at, expires_at) VALUES ('verify-probe','test','DONE',NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR)); INSERT INTO ${DB}.idempotency_record (idem_key, scope, status, created_at, expires_at) VALUES ('verify-probe','test','DONE',NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR));")"
IDEM_LEFT="$(sql "SELECT COUNT(*) FROM ${DB}.idempotency_record WHERE idem_key='verify-probe';")"
if [[ "$IDEM" == "1" ]] && [[ "$IDEM_LEFT" == "0" ]]; then
  res A3 1 "幂等表 (idem_key, scope) 唯一约束生效" "重复插入被拒绝；无残留"
else
  res A3 0 "幂等唯一约束异常" "被拒=${IDEM} 残留=${IDEM_LEFT}"
fi

echo "--- A4 事务原子性 ---"
sql_expect_fail "START TRANSACTION; INSERT INTO ${DB}.idempotency_record (idem_key, scope, status, created_at, expires_at) VALUES ('verify-rollback','test','DONE',NOW(),NOW()); SELECT 1/0;" >/dev/null
TX_LEFT="$(sql "SELECT COUNT(*) FROM ${DB}.idempotency_record WHERE idem_key='verify-rollback';")"
if [[ "$TX_LEFT" == "0" ]]; then
  res A4 1 "事务失败后不留半写状态" "错误语句触发回滚；残留=0"
else
  res A4 0 "事务失败后仍留下数据（违反原子性）" "残留=${TX_LEFT}"
fi

# ===========================================================================
echo
echo "############ B. 真实 Redis Streams ############"
echo

echo "--- B1 Redis 低配配置实际生效值 ---"
POLICY="$(rcli "CONFIG GET maxmemory-policy" | tail -n 1)"
MAXMEM="$(rcli "CONFIG GET maxmemory" | tail -n 1)"
AOF="$(rcli "CONFIG GET appendonly" | tail -n 1)"
FSYNC="$(rcli "CONFIG GET appendfsync" | tail -n 1)"
SAVE_CFG="$(rcli "CONFIG GET save" | tail -n 1)"
B1_OK=1; B1_EV=""
if [[ "$POLICY" != "noeviction" ]]; then B1_OK=0; B1_EV="${B1_EV} maxmemory-policy=${POLICY}(期望 noeviction)"; fi
if [[ "$MAXMEM" != "67108864" ]]; then B1_OK=0; B1_EV="${B1_EV} maxmemory=${MAXMEM}(期望 67108864)"; fi
if [[ "$AOF" != "yes" ]]; then B1_OK=0; B1_EV="${B1_EV} appendonly=${AOF}(期望 yes)"; fi
if [[ "$FSYNC" != "everysec" ]]; then B1_OK=0; B1_EV="${B1_EV} appendfsync=${FSYNC}(期望 everysec)"; fi
if (( B1_OK == 1 )); then
  res B1 1 "队列键不会被淘汰，AOF 每秒刷盘（丢失窗口 <= 1 秒）" \
    "policy=${POLICY} maxmemory=$(( MAXMEM / 1048576 ))MiB appendonly=${AOF} appendfsync=${FSYNC} save='${SAVE_CFG}'"
else
  res B1 0 "Redis 配置未按预期生效（配置文件可能被解析失败或未挂载）" "${B1_EV}"
fi

if rcli "KEYS *" | grep -qi 'unknown command'; then
  res B1b 1 "危险命令 KEYS 已改名生效" "KEYS 返回 unknown command"
else
  res B1b 0 "KEYS 仍可用：rename-command 未生效" "请检查 deploy/redis/redis-lowmem.conf 是否被加载"
fi

echo "--- B2 探测队列可创建、消费组可建 ---"
rcli "DEL ${PROBE}" >/dev/null 2>&1 || true
rcli "XGROUP CREATE ${PROBE} ${PROBE_GROUP} 0 MKSTREAM" >/dev/null 2>&1 || true
if [[ "$(rcli "TYPE ${PROBE}")" == "stream" ]]; then
  res B2 1 "独立探测 stream 已建立（与 eval.jobs 隔离，不干扰真实队列）" "key=${PROBE} group=${PROBE_GROUP}"
else
  res B2 0 "探测 stream 创建失败" "TYPE ${PROBE} = $(rcli "TYPE ${PROBE}")"
fi

# --- B3: 卡住的消息能被重新认领并进入终态 -----------------------------------
#
# 这是"Worker 处理中退出"的确定性复现：
#   1) 用假消费者 crasher 把消息读走但**不 ACK** —— 等价于"处理到一半进程被杀"；
#   2) 用**低认领超时**的一次性 Worker 启动 —— 它必须通过 XAUTOCLAIM 把这条捡回去；
#   3) 断言 PEL 被清空 —— 即消息进入了终态（被处理并 ACK，或被判定进死信）。
#
# 为什么不"真的 kill 进程再赌时序"：那种测法依赖调度时序，会时红时绿；
# 而这里走的是**同一条代码路径**（_reclaim → _process），结论同样有效。
echo "--- B3 崩溃后重新认领（XAUTOCLAIM）---"
rcli "XADD ${PROBE} '*' runId 999999001 datasetId 1 appId 1 mode GUARD_ON attempt 1 traceId ''" >/dev/null

# 假消费者读走不 ACK
rcli "XREADGROUP GROUP ${PROBE_GROUP} crasher COUNT 1 STREAMS ${PROBE} >" >/dev/null
PEL_BEFORE="$(rcli "XPENDING ${PROBE} ${PROBE_GROUP}" | head -n 1 | grep -oE '^[0-9]+' | head -n 1)"
if is_int "$PEL_BEFORE" && (( PEL_BEFORE > 0 )); then
  ok "已模拟崩溃：消息在 PEL 中未被 ACK（PEL=${PEL_BEFORE}）"

  # 一次性 Worker 跑在探测 stream 上，认领超时压到 1 秒（生产默认 60 秒）
  compose run --rm --no-deps \
    -e TRUSTAGENT_EVAL_STREAM_KEY="${PROBE}" \
    -e TRUSTAGENT_EVAL_DEAD_LETTER_KEY="${PROBE_DLQ}" \
    -e TRUSTAGENT_EVAL_CONSUMER_GROUP="${PROBE_GROUP}" \
    -e TRUSTAGENT_EVAL_CONSUMER_NAME="verify-once" \
    -e TRUSTAGENT_EVAL_RECLAIM_IDLE_MS=1000 \
    -e TRUSTAGENT_EVAL_MAX_DELIVERIES=2 \
    ai-worker python -m app.eval.worker --once --drain-seconds 2 >/dev/null 2>&1 || true
  sleep 1

  PEL_AFTER="$(rcli "XPENDING ${PROBE} ${PROBE_GROUP}" | head -n 1 | grep -oE '^[0-9]+' | head -n 1)"
  declare -i DLQ_AFTER=0
  if [[ "$(rcli "EXISTS ${PROBE_DLQ}")" == "1" ]]; then
    DLQ_AFTER="$(rcli "XLEN ${PROBE_DLQ}")"
  fi
  if is_int "$PEL_AFTER" && (( PEL_AFTER < PEL_BEFORE )); then
    res B3 1 "卡住的消息被重新认领并进入终态（PEL 已释放）" \
      "PEL ${PEL_BEFORE} -> ${PEL_AFTER}；死信队列长度=${DLQ_AFTER}"
  else
    res B3 0 "消息仍卡在 PEL 中未被认领" \
      "PEL ${PEL_BEFORE} -> ${PEL_AFTER:-未知}；说明 XAUTOCLAIM 路径未生效，任务会永远停在 RUNNING"
  fi
else
  res B3 0 "无法把消息读入 PEL，验证未成立" "PEL=${PEL_BEFORE:-未知}"
fi

# --- B5: 超过最大投递次数进入死信 -------------------------------------------
echo "--- B5 超过最大投递次数转入死信 ---"
rcli "DEL ${PROBE_DLQ}" >/dev/null 2>&1 || true
BOGUS="$(rcli "XADD ${PROBE} '*' runId 999999002 datasetId 1 appId 1 mode GUARD_ON attempt 1 traceId ''")"
# 人为抬高投递次数：每次 XCLAIM 换一个消费者都会让 times_delivered +1
rcli "XREADGROUP GROUP ${PROBE_GROUP} crasher1 COUNT 1 STREAMS ${PROBE} >" >/dev/null
rcli "XCLAIM ${PROBE} ${PROBE_GROUP} crasher2 0 ${BOGUS}" >/dev/null
rcli "XCLAIM ${PROBE} ${PROBE_GROUP} crasher3 0 ${BOGUS}" >/dev/null
DELIVERED="$(rcli "XPENDING ${PROBE} ${PROBE_GROUP} - + 1" | grep -A4 "${BOGUS}" | grep -oE '[0-9]+' | tail -n 1)"
ok "已把投递次数抬高到 ${DELIVERED:-未知}"

# max-deliveries=1：delivered(>=2) > 1，应当直接进死信
compose run --rm --no-deps \
  -e TRUSTAGENT_EVAL_STREAM_KEY="${PROBE}" \
  -e TRUSTAGENT_EVAL_DEAD_LETTER_KEY="${PROBE_DLQ}" \
  -e TRUSTAGENT_EVAL_CONSUMER_GROUP="${PROBE_GROUP}" \
  -e TRUSTAGENT_EVAL_CONSUMER_NAME="verify-dlq" \
  -e TRUSTAGENT_EVAL_RECLAIM_IDLE_MS=1000 \
  -e TRUSTAGENT_EVAL_MAX_DELIVERIES=1 \
  ai-worker python -m app.eval.worker --once --drain-seconds 2 >/dev/null 2>&1 || true
sleep 1

DLQ_LEN="$(rcli "EXISTS ${PROBE_DLQ}" | grep -q '^1$' && rcli "XLEN ${PROBE_DLQ}" || echo 0)"
DLQ_REASON="$(rcli "XRANGE ${PROBE_DLQ} - + COUNT 1" | grep -A1 '^reason$' | tail -n 1)"
if is_int "$DLQ_LEN" && (( DLQ_LEN >= 1 )); then
  res B5 1 "超过最大投递次数的消息被转入死信队列，不会无限重投" \
    "死信长度=${DLQ_LEN}；reason=${DLQ_REASON:-（未取到）}"
else
  res B5 0 "消息未进入死信队列" \
    "死信长度=${DLQ_LEN}；超限消息会一直留在 PEL 里被反复重投"
fi

# --- B6: 真实评测队列的端到端（走 eval.jobs，由常驻 Worker 消费）-----------
echo "--- B6 端到端：通过真实队列跑完一次双配置评测 ---"
# 口令走 stdin（api_stdin 的 --data @-），不进 argv。
LOGIN="$(jq -nc --arg u "$(get_env TRUSTAGENT_BOOTSTRAP_ADMIN_USERNAME '')" \
              --arg p "$(get_env TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD '')" \
              '{username:$u,password:$p}' \
  | api_stdin POST /api/auth/login)"
TOKEN="$(data_of "$LOGIN" | jq -r '.token // empty')"
if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  skip B6 "管理员登录失败，跳过端到端评测" "请检查 .env 里的引导管理员账号"
else
  ok "管理员已登录"

  DATASET_ID="$(data_of "$(api GET /api/eval/datasets '' "$TOKEN")" \
    | jq -r 'if type=="array" then (.[0].id // empty) else (.records[0].id // empty) end')"
  if [[ -z "$DATASET_ID" || "$DATASET_ID" == "null" ]]; then
    skip B6 "没有可用的评测数据集" "请先导入 datasets/synthetic_eval_v1.jsonl（见 docs/BENCHMARK.md）"
  else
    ok "使用数据集 #${DATASET_ID}"
    APP_ID="$(data_of "$(api POST /api/apps \
      "$(jq -nc '{code:"verify-eval",name:"验证用 Agent（评测）",description:"由 verify-stack.sh 创建",systemPrompt:"你是测试助手。"}')" \
      "$TOKEN")" | jq -r '.id // empty')"

    if [[ -z "$APP_ID" || "$APP_ID" == "null" ]]; then
      skip B6 "创建评测用 Agent 失败" "见 /api/apps 的响应"
    else
      RUN_ID="$(data_of "$(api POST /api/eval/runs \
        "$(jq -nc --argjson d "$DATASET_ID" --argjson a "$APP_ID" '{datasetId:$d,appId:$a,mode:"BOTH"}')" \
        "$TOKEN" "Idempotency-Key: verify-e2e-$(date +%s)")" | jq -r '.id // empty')"

      if [[ -z "$RUN_ID" || "$RUN_ID" == "null" ]]; then
        skip B6 "提交评测任务失败" "见 /api/eval/runs 的响应"
      else
        ok "评测任务 #${RUN_ID} 已提交，等待常驻 Worker 消费 ..."
        TIMEOUT=300
        (( FULL_EVAL == 1 )) && TIMEOUT=1800
        DEADLINE=$(( $(date +%s) + TIMEOUT ))
        ST="PENDING"
        while (( $(date +%s) < DEADLINE )); do
          ST="$(data_of "$(api GET "/api/eval/runs/${RUN_ID}" '' "$TOKEN")" | jq -r '.status // "UNKNOWN"')"
          [[ "$ST" == "SUCCEEDED" || "$ST" == "FAILED" ]] && break
          sleep 10
        done

        R1="$(sql "SELECT COUNT(*) FROM ${DB}.eval_case_result WHERE run_id=${RUN_ID} AND guard_enabled=1;")"
        R0="$(sql "SELECT COUNT(*) FROM ${DB}.eval_case_result WHERE run_id=${RUN_ID} AND guard_enabled=0;")"
        TOTAL="$(sql "SELECT total_cases FROM ${DB}.eval_run WHERE id=${RUN_ID};")"
        if [[ "$ST" == "SUCCEEDED" ]] && is_int "$R1" && is_int "$R0" && (( R1 > 0 )) && (( R0 > 0 )); then
          res B6 1 "评测真实走完 Redis 队列，防护开/关两组结果都落库" \
            "run #${RUN_ID} status=${ST} total_cases=${TOTAL} guardOn=${R1}行 guardOff=${R0}行"
          METRICS="$(sql "SELECT guard_metrics_json FROM ${DB}.eval_run WHERE id=${RUN_ID};")"
          BASELINE="$(sql "SELECT baseline_metrics_json FROM ${DB}.eval_run WHERE id=${RUN_ID};")"
          echo "       [防护开启指标] $(printf '%s' "$METRICS" | head -c 300)"
          echo "       [防护关闭指标] $(printf '%s' "$BASELINE" | head -c 300)"

          echo "--- A5 真实数据上的 JSON 与时间字段 ---"
          CREATED_OK="$(sql "SELECT COUNT(*) FROM ${DB}.eval_case_result WHERE run_id=${RUN_ID} AND created_at IS NOT NULL;")"
          METRICS_VALID=0
          BASELINE_VALID=0
          [[ -n "$METRICS" ]] && METRICS_VALID="$(sql "SELECT JSON_VALID(CAST('$(printf '%s' "$METRICS" | sed "s/'/''/g")' AS CHAR));")"
          [[ -n "$BASELINE" ]] && BASELINE_VALID="$(sql "SELECT JSON_VALID(CAST('$(printf '%s' "$BASELINE" | sed "s/'/''/g")' AS CHAR));")"
          if [[ "$METRICS_VALID" == "1" ]] && [[ "$BASELINE_VALID" == "1" ]] && is_int "$CREATED_OK" && (( CREATED_OK > 0 )); then
            res A5 1 "两组指标以合法 JSON 落库，时间字段正常" \
              "guard JSON 有效=1 baseline JSON 有效=1；created_at 非空=${CREATED_OK} 行"
          else
            res A5 0 "JSON 或时间字段异常" \
              "guard JSON 有效=${METRICS_VALID} baseline JSON 有效=${BASELINE_VALID} created_at 非空=${CREATED_OK}"
          fi
        else
          res B6 0 "评测未正常完成" "status=${ST} total_cases=${TOTAL} guardOn=${R1} guardOff=${R0}"
        fi
      fi
    fi
  fi
fi

# ===========================================================================
echo
echo "############ C. 访问控制 ############"
echo

C1="$(code_of "$(api GET /api/apps)")"
if [[ "$C1" == "401" ]]; then
  res C1 1 "未登录访问受保护接口被拒绝" "HTTP ${C1}"
else
  res C1 0 "未登录仍可访问" "HTTP ${C1}（期望 401）"
fi

if [[ -n "${TOKEN:-}" && "$TOKEN" != "null" ]]; then
  SUF="$(date +%s)"
  DEV_USER="verify-dev-${SUF}"
  api POST /api/users \
    "$(jq -nc --arg u "$DEV_USER" \
      '{username:$u,password:"Verify#Demo12345",displayName:"验证用开发者",email:"verify@example.com",roles:["DEVELOPER"]}')" \
    "$TOKEN" >/dev/null
  DEV_LOGIN="$(api POST /api/auth/login \
    "$(jq -nc --arg u "$DEV_USER" '{username:$u,password:"Verify#Demo12345"}')")"
  DEV_TOKEN="$(data_of "$DEV_LOGIN" | jq -r '.token // empty')"

  if [[ -n "$DEV_TOKEN" && "$DEV_TOKEN" != "null" ]]; then
    C2="$(code_of "$(api GET /api/users '' "$DEV_TOKEN")")"
    if [[ "$C2" == "403" ]]; then
      res C2 1 "DEVELOPER 访问管理员接口被拒绝（RBAC 生效）" "HTTP ${C2}"
    else
      res C2 0 "DEVELOPER 越权访问管理员接口" "HTTP ${C2}（期望 403）"
    fi

    # 跨用户对象越权：DEV 去读写 ADMIN 拥有的应用策略
    OTHER_APP="$(data_of "$(api POST /api/apps \
      "$(jq -nc '{code:"verify-other",name:"他人应用",description:"用于越权验证",systemPrompt:"x"}')" \
      "$TOKEN")" | jq -r '.id // empty')"
    if [[ -n "$OTHER_APP" && "$OTHER_APP" != "null" ]]; then
      C4_READ="$(code_of "$(api GET "/api/apps/${OTHER_APP}/policy" '' "$DEV_TOKEN")")"
      C4_WRITE="$(code_of "$(api PUT "/api/apps/${OTHER_APP}/policy" \
        "$(jq -nc '{guardEnabled:false,maxToolCalls:99,maxInputLength:99999,taskTimeoutMs:60000,domainWhitelist:[],deniedParamRules:[],confirmSideEffects:false,injectionDetectionEnabled:false,piiMaskingEnabled:false,outputGuardEnabled:false,version:1}')" \
        "$DEV_TOKEN")")"
      if [[ ( "$C4_READ" == "403" || "$C4_READ" == "404" ) && ( "$C4_WRITE" == "403" || "$C4_WRITE" == "404" ) ]]; then
        res C4 1 "跨用户对象访问被拒绝（读与写都被拦）" "读=HTTP ${C4_READ} 写=HTTP ${C4_WRITE}"
      else
        res C4 0 "存在跨用户越权" "读=HTTP ${C4_READ} 写=HTTP ${C4_WRITE}（期望 403/404）"
      fi

      # 越权写是否真的没生效：直接查库确认策略没被动过
      GUARD_STATE="$(sql "SELECT guard_enabled FROM ${DB}.security_policy WHERE app_id=${OTHER_APP};")"
      if [[ ( "$C4_WRITE" == "403" || "$C4_WRITE" == "404" ) && "$GUARD_STATE" != "0" ]]; then
        res C6 1 "普通用户无法通过构造请求关闭防护（库中策略未被改动）" \
          "应用 #${OTHER_APP} guard_enabled=${GUARD_STATE:-未创建}"
      else
        res C6 0 "防护开关可能被普通用户改动" \
          "写=HTTP ${C4_WRITE}；库中 guard_enabled=${GUARD_STATE:-未知}"
      fi
    else
      skip C4 "创建对照应用失败，跳过跨用户越权验证" ""
    fi
  else
    skip C2 "无法创建或登录普通用户，跳过 RBAC 验证" "请检查 POST /api/users 的行为"
  fi
else
  skip C2 "无管理员 Token，跳过 RBAC 验证" ""
fi

C3_RESP="$(api POST /internal/eval/callback '{"runId":1,"type":"COMPLETED"}')"
C3="$(code_of "$C3_RESP")"
C3_BODY="$(body_of "$C3_RESP")"
# 只判 404 太松（见 smoke.sh 的 S1 同类说明）：Caddy 整体挂掉、
# 或路径被指到别处后返回 404，都会被记成 PASS。
# 控制面的响应体是信封 JSON，Caddy 的拒绝是纯文本 —— 用这一点区分。
if [[ "$C3" == "404" ]] && ! printf '%s' "$C3_BODY" | jq -e 'has("code") and has("message")' >/dev/null 2>&1; then
  res C3 1 "代理入口不存在 /internal/**（请求根本到不了服务端）" "HTTP ${C3}"
elif [[ "$C3" == "404" ]]; then
  res C3 0 "内部接口返回了控制面风格的信封响应" "HTTP ${C3}；响应体形如信封，请核对代理规则"
else
  res C3 0 "内部接口在代理入口可达" "HTTP ${C3}（期望 404；若是 401 说明请求打到了服务端，代理规则没生效）"
fi

C5_BAD=0; C5_EV=""
for svc in mysql redis ai-service backend; do
  PUB="$(docker inspect "trustagent-${svc}" -f '{{json .NetworkSettings.Ports}}' 2>/dev/null \
        | jq -r '(. // {}) | to_entries | map(select(.value != null)) | length')"
  if [[ "${PUB:-0}" != "0" ]]; then
    C5_BAD=$(( C5_BAD + 1 ))
    C5_EV="${C5_EV} ${svc}发布了${PUB}个端口;"
  fi
done
if (( C5_BAD == 0 )); then
  res C5 1 "MySQL / Redis / 执行面 / 控制面 均未在宿主机发布端口" "只有 web 绑定 127.0.0.1"
else
  res C5 0 "有服务把端口发布到了宿主机" "${C5_EV}"
fi

# ===========================================================================
echo
echo "############ D. 资源 ############"
echo
mem_snapshot
"$SCRIPT_DIR/status.sh" --record >/dev/null 2>&1 && ok "资源已记入 .local/server/resource-log.csv"
for svc in mysql redis ai-service ai-worker backend web; do
  n="trustagent-${svc}"
  docker inspect "$n" >/dev/null 2>&1 || continue
  [[ "$(docker inspect -f '{{.State.OOMKilled}}' "$n")" == "true" ]] && bad "${n} 曾被 OOMKilled"
  RST="$(docker inspect -f '{{.RestartCount}}' "$n")"
  (( RST > 0 )) && warn "${n} 重启过 ${RST} 次"
done

# 清理探测用的 stream，避免留下无用数据
rcli "DEL ${PROBE}" >/dev/null 2>&1 || true
rcli "DEL ${PROBE_DLQ}" >/dev/null 2>&1 || true
ok "探测 stream 已清理"

if (( FULL_EVAL == 0 )); then
  echo
  echo " 提示：100 条 × 双配置的完整评测需要加 --full-eval。"
  echo "       刻意不默认执行——它会把 CPU 与内存占用推高，应先确认小规模通过。"
fi

echo
echo "============================================================"
echo " 验证结果：通过 ${PASS}   失败 ${FAIL}   跳过 ${SKIP}"
if (( FAIL > 0 )); then
  echo " 有项目未通过：**不要**据此刻宣布验收通过，先定位上面 [FAIL] 的原因。"
else
  echo " SKIP 的项目属于**未验证**，不能算作通过。"
fi
echo "============================================================"
(( FAIL == 0 )) || exit 1

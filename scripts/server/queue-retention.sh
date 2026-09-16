#!/usr/bin/env bash
# ============================================================================
# Redis Stream 保留策略（安全裁剪）
#
# 背景：RedisEvalJobPublisher 刻意**不做** MAXLEN 自动裁剪，理由是
#   "按条数裁剪会丢掉还没被处理的任务"。
# 那个理由成立，但代价是 stream 会无限增长；而 Redis 的 maxmemory-policy
# 是 noeviction，所以增长到上限后**所有写操作都会失败**（包括限流与入队）。
# 两个需求必须在"只裁剪已确认的前缀"这个点上汇合。
#
# 安全裁剪的判据（这是本脚本存在的全部意义）：
#
#   已投递的条目 id 一定是连续前缀（XREADGROUP 按序投递）。
#   设：
#     L = 消费组 last-delivered-id   —— 大于它的条目可能从未投递
#     P = PEL 里最小的一条 id        —— 小于它的已投递条目必然都已 ACK
#   裁剪到 min(L, P) 是安全的：
#     * 小于 P 的条目：都已 ACK（未 ACK 的一定在 PEL 里，不会小于 P）
#     * 大于 L 的条目（未投递）：id 都大于 L ≥ min(L,P)，不会被裁掉
#   所以"未处理的任务"永远不会被删掉 —— 这正是原始注释想保证的性质。
#
#   若 PEL 为空（P 不存在）：所有已投递条目都已 ACK，裁剪到 L 即安全。
#
# 用法：
#   ./scripts/server/queue-retention.sh              # 只看现状（dry-run）
#   ./scripts/server/queue-retention.sh --apply      # 实际执行裁剪
#   ./scripts/server/queue-retention.sh --apply --trim-dlq 5000
#       # 额外把死信队列裁剪到最近 5000 条。
#       # 死信里的任务已经终态失败，裁剪只影响"保留多少证据"，不影响处理。
#       # 默认**不裁剪死信**：它是排查问题的原始材料。
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

APPLY=0
DLQ_KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply)    APPLY=1; shift ;;
    --trim-dlq) DLQ_KEEP="$2"; shift 2 ;;
    *) die "未知参数: $1" ;;
  esac
done

require_docker

STREAM="eval.jobs"
GROUP="cg:eval"
DLQ="eval.jobs.dlq"

echo "============================================================"
echo " Redis Stream 保留策略  $([[ "$APPLY" == "1" ]] && echo "（执行模式）" || echo "（dry-run，不修改任何东西）")"
echo "============================================================"

docker inspect trustagent-redis >/dev/null 2>&1 || die "Redis 容器不存在"
[[ "$(docker inspect -f '{{.State.Status}}' trustagent-redis)" == "running" ]] || die "Redis 未运行"

# 在容器内执行 redis-cli：口令从容器环境变量取，不出现在宿主进程列表或日志里
rcli() {
  docker exec trustagent-redis sh -c \
    'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" '"$*" 2>/dev/null | tr -d '\r'
}

# ---------------------------------------------------------------------------
echo
echo "[1] 现状"
# ---------------------------------------------------------------------------
if ! rcli "EXISTS $STREAM" | grep -q '^1$'; then
  ok "stream ${STREAM} 不存在（还没有任何评测任务）。无需裁剪。"
  exit 0
fi

STREAM_LEN="$(rcli "XLEN $STREAM")"
ok "stream 长度: ${STREAM_LEN}"

# 消费组必须存在，否则没有"已投递"的概念，也就无从判断哪些能裁
if ! rcli "XINFO GROUPS $STREAM" | grep -qx "$GROUP"; then
  bad "消费组 ${GROUP} 不存在。"
  echo "         Worker 还没启动过，此时无法判断哪些条目已被消费。"
  echo "         **拒绝执行裁剪**：盲目裁剪可能删掉尚未处理的任务。"
  exit 1
fi

# last-delivered-id 在 XINFO GROUPS 输出里紧跟在 'last-delivered-id' 标签的下一行
LAST_DELIVERED="$(rcli "XINFO GROUPS $STREAM" | awk '/^last-delivered-id$/{getline; print; exit}')"
ok "消费组 last-delivered-id: ${LAST_DELIVERED:-未知}"

# PEL 里最小的一条 id
PENDING_MIN="$(rcli "XPENDING $STREAM $GROUP - + 1" | grep -oE '[0-9]+-[0-9]+' | head -n 1 || true)"
PENDING_COUNT="$(rcli "XPENDING $STREAM $GROUP" | head -n 1 | grep -oE '[0-9]+' | head -n 1 || echo 0)"
if [[ -n "$PENDING_MIN" ]]; then
  warn "PEL 中还有 ${PENDING_COUNT} 条未确认，最小 id=${PENDING_MIN}"
else
  ok "PEL 为空（所有已投递条目都已 ACK）"
fi

# ---------------------------------------------------------------------------
echo
echo "[2] 计算安全裁剪点"
# ---------------------------------------------------------------------------
# 比较两个 stream id：格式为 <毫秒>-<序号>，按数值元组比较
id_lt() {
  local a="$1" b="$2"
  [[ "$a" == "$b" ]] && return 1
  local a1="${a%%-*}" a2="${a##*-}" b1="${b%%-*}" b2="${b##*-}"
  if (( a1 < b1 )); then return 0; fi
  if (( a1 > b1 )); then return 1; fi
  (( a2 < b2 ))
}

if [[ -z "$LAST_DELIVERED" ]]; then
  die "无法读取 last-delivered-id，拒绝继续"
fi

SAFE_ID="$LAST_DELIVERED"
if [[ -n "$PENDING_MIN" ]]; then
  if id_lt "$PENDING_MIN" "$LAST_DELIVERED"; then
    SAFE_ID="$PENDING_MIN"
  fi
fi

ok "安全裁剪点: ${SAFE_ID}"
echo "         含义：id 小于 ${SAFE_ID} 的条目都已确认，可以安全删除；"
echo "               未确认的与未投递的条目都不在这个范围内。"

# 预计能删多少
TO_TRIM="$(rcli "XRANGE $STREAM - ${SAFE_ID}" | grep -cE '^[0-9]+-[0-9]+$' || echo '?')"
echo "         预计影响条目数（含边界）: ${TO_TRIM}"

# ---------------------------------------------------------------------------
echo
echo "[3] 执行"
# ---------------------------------------------------------------------------
if (( APPLY == 0 )); then
  warn "dry-run：未做任何修改。确认上面的数字合理后，用 --apply 执行。"
else
  # XTRIM MINID 删除 id **小于** 给定值的条目，正好符合上面的语义
  if rcli "XTRIM $STREAM MINID $SAFE_ID" >/dev/null; then
    NEW_LEN="$(rcli "XLEN $STREAM")"
    ok "裁剪完成：${STREAM_LEN} -> ${NEW_LEN}"
  else
    die "XTRIM 执行失败"
  fi

  # 再确认一次"没有误删未确认的消息"
  REMAIN_PENDING="$(rcli "XPENDING $STREAM $GROUP" | head -n 1 | grep -oE '[0-9]+' | head -n 1 || echo 0)"
  if [[ -n "$PENDING_MIN" ]] && [[ "$REMAIN_PENDING" =~ ^[0-9]+$ ]] && (( REMAIN_PENDING < PENDING_COUNT )); then
    bad "裁剪后未确认数从 ${PENDING_COUNT} 降到 ${REMAIN_PENDING} —— 疑似误删了未确认消息！"
    echo "         请立即用最近的备份检查 eval_run 状态，不要继续裁剪。"
    exit 1
  fi
  ok "未确认数未被减少（${REMAIN_PENDING}），未误删待处理任务"
fi

# ---------------------------------------------------------------------------
echo
echo "[4] 死信队列"
# ---------------------------------------------------------------------------
DLQ_LEN_NOW="$(rcli "EXISTS $DLQ" | grep -q '^1$' && rcli "XLEN $DLQ" || echo 0)"
echo "  当前死信条数: ${DLQ_LEN_NOW}"
if (( DLQ_KEEP > 0 )); then
  if [[ "$DLQ_LEN_NOW" =~ ^[0-9]+$ ]] && (( DLQ_LEN_NOW > DLQ_KEEP )); then
    if (( APPLY == 1 )); then
      # 死信里的任务已终态失败，这里按条数保留最近 N 条是安全的
      # （不会影响任何待处理任务）。但会减少可追溯的证据。
      rcli "XTRIM $DLQ MAXLEN ~ $DLQ_KEEP" >/dev/null && ok "死信已裁剪到约 ${DLQ_KEEP} 条"
    else
      warn "dry-run：将把死信从 ${DLQ_LEN_NOW} 裁剪到约 ${DLQ_KEEP} 条"
    fi
  else
    ok "死信条数未超过阈值，无需裁剪"
  fi
else
  echo "  未指定 --trim-dlq，死信保持不变（默认保留全部，便于排查）。"
fi

echo
echo "============================================================"
echo " 建议：把本脚本的 dry-run 加入定期巡检；确需裁剪时再 --apply。"
echo " 不要用 MAXLEN 裁剪 ${STREAM} 本身 —— 那会删掉未处理的任务。"
echo "============================================================"

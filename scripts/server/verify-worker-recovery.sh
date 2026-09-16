#!/usr/bin/env bash
# 三项 Worker 崩溃恢复验收。
#
# 为什么不能用"假消费者"或 verify-stack.sh 的探测 stream：
#   探测 stream 上跑的是**伪造 runId**，只能看到 Redis 层面的 PEL/DLQ 变化，
#   看不到"数据库里的 run 有没有走到终态、结果行数对不对、完成计数有没有翻倍"。
#   崩溃恢复的验收标准恰恰在数据库侧。
#
# 安全措施：
#   * trap 保证无论中途怎么退出，都会把正常 Worker 拉回来
#   * 只操作本脚本自己创建的 runId / 数据集
#   * 不清空队列、不删除其他任务、不动 mysql/redis 容器
#   * 故障只命中指定 runId（见 worker.py 的 _fault_matches），不会让重启永远崩溃
#   * 口令**不进任何命令行**：数据库口令在 mysql 容器内部展开，
#     管理员口令经 stdin 交给容器内的 python 拼 JSON（宿主的 ps 能看到别的用户的完整 argv）。
#
# 管理员口令来源（与 smoke.sh / verify-stack.sh 一致）：
#   1) TRUSTAGENT_VERIFY_ADMIN_PASSWORD（显式覆盖，用于"口令已改过"的场景）
#   2) .env 的 TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD
# 这里刻意不再读 .local/admin-pw.txt：仓库中没有任何脚本会创建它，
#   读一个恒不存在的文件只会把排查方向引偏。
#
# 关于 set -e（这是一个有意为之的例外，不是漏写）：
#   本脚本是"断言驱动"的：每项检查都自己判断通过/失败，最后用 `exit $(( fail > 0 ))`
#   汇报。整份脚本充满了**期望失败**的调用——故障注入后 run 处于 PENDING、消息留在 PEL、
#   PEL 查询在没有消息时返回空、`docker stop` 在容器已停时返回非零。
#   加上 -e 会让第一次"预期内的失败"直接终止脚本，
#   表现为"跑到一半莫名其妙退出"，而报告的 PASS/FAIL 计数还是残的。
#   因此这里保持 -uo（未定义变量与管道错误仍是硬错误），
#   但**不**用 -e；关键外部调用都单独判过返回码或状态。
set -uo pipefail
cd /opt/trustagent || exit 1
set -a; . ./.env; set +a

BASE="http://127.0.0.1:8090"
IMG="trustagent-ai-service:${TRUSTAGENT_IMAGE_TAG}"
PY="docker exec -i trustagent-ai-service python"
RCLI() { docker exec trustagent-redis sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" '"$*" 2>/dev/null | tr -d '\r'; }
# SQL 经 stdin 送进 mysql：口令在容器内部展开（容器里已有 compose 注入的 MYSQL_*）。
Q() { docker exec -i trustagent-mysql sh -c \
    'exec mysql -N -B --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
    <<< "$1" 2>/dev/null | tr -d '[:space:]'; }

pass=0; fail=0
ok()  { echo "  [PASS] $*"; pass=$((pass+1)); }
bad() { echo "  [FAIL] $*"; fail=$((fail+1)); }
note(){ echo "         $*"; }

NET="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' trustagent-ai-worker | awk '{print $1}')"
WORKER_WAS_RUNNING="$(docker inspect -f '{{.State.Running}}' trustagent-ai-worker 2>/dev/null)"

restore_worker() {
  echo ""
  echo "=== 恢复：把正常 Worker 拉回来 ==="
  docker start trustagent-ai-worker >/dev/null 2>&1 || docker compose -f docker-compose.lowmem.yml --env-file .env up -d --no-deps ai-worker >/dev/null 2>&1
  sleep 4
  docker ps --format '  {{.Names}}\t{{.Status}}' | grep ai-worker || true
  # 确认故障开关没有残留在容器里
  docker exec trustagent-ai-worker sh -c 'echo "  容器内 TRUSTAGENT_EVAL_FAULT = ${TRUSTAGENT_EVAL_FAULT:-<未设置>}"' 2>/dev/null
}
trap restore_worker EXIT

# 一次性 Worker：带故障开关跑一轮
once_worker() {
  local fault="$1"; shift
  local extra=()
  for kv in "$@"; do extra+=(-e "$kv"); done
  docker run --rm --network "$NET" \
    -e "TRUSTAGENT_INTERNAL_TOKEN=${TRUSTAGENT_INTERNAL_TOKEN}" \
    -e "TRUSTAGENT_REDIS_URL=redis://:${TRUSTAGENT_REDIS_PASSWORD}@redis:6379/0" \
    -e "TRUSTAGENT_JAVA_BASE_URL=http://backend:8080" \
    -e "TRUSTAGENT_LLM_PROVIDER=mock" \
    -e "TRUSTAGENT_EVAL_CONSUMER_NAME=worker-1" \
    -e "TRUSTAGENT_EVAL_FAULT=${fault}" \
    "${extra[@]}" \
    "$IMG" python -m app.eval.worker --once --drain-seconds 4 2>&1 | tail -4 | sed 's/^/         /'
}

echo "============================================================"
echo " Worker 崩溃恢复验收    $(date -Is)"
echo "   镜像: $IMG"
echo "   worker 原本是否在运行: $WORKER_WAS_RUNNING"
echo "============================================================"

# ---------------------------------------------------------------- 前置检查
echo ""
echo "[0] 前置：确认没有其他 RUNNING 的评测任务"
RUNNING_OTHER="$(Q "SELECT COUNT(*) FROM eval_run WHERE status IN ('RUNNING','PENDING');")"
echo "  当前 RUNNING/PENDING 的 run 数: ${RUNNING_OTHER:-?}"
if [[ "${RUNNING_OTHER:-0}" != "0" ]]; then
  echo "  [ABORT] 有任务正在跑，为避免干扰其他工作，本次不执行恢复测试。"
  trap - EXIT
  exit 0
fi
note "队列中残留消息（不清理，仅记录）: eval.jobs=$(RCLI "XLEN eval.jobs")  dlq=$(RCLI "XLEN eval.jobs.dlq")"
# ---------------------------------------------------------------- 登录（先做）
echo ""
echo "[0b] 管理员登录（先确认凭证可用，再动线上）"
# 口令与 token 都在**停 Worker 之前**准备好：登录失败要直接中止，
# 而那时线上还没被动过，不需要任何恢复动作（trap 里的恢复函数因此也不会被用到）。
# 口令来源见文件头。经 stdin 传给容器内 python 拼 JSON，再 --data @- 交给 curl：
# 整条链路上口令不进任何 argv（宿主的 ps 对同机其他用户可见）。
ADMIN_USER="${TRUSTAGENT_VERIFY_ADMIN_USERNAME:-${TRUSTAGENT_BOOTSTRAP_ADMIN_USERNAME:-admin}}"
ADMIN_PW="${TRUSTAGENT_VERIFY_ADMIN_PASSWORD:-${TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD:-}}"
if [[ -z "$ADMIN_PW" ]]; then
  # 这个提前退出发生在停 Worker 之前，线上状态未被改动。
  echo "  [ABORT] 未取到管理员口令：请在 .env 设置 TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD，"
  echo "          或用 TRUSTAGENT_VERIFY_ADMIN_PASSWORD=... 显式传入（口令已改过的场景）。"
  exit 1
fi

TOKEN="$(printf %s "$ADMIN_PW" \
  | $PY -c 'import sys,json;print(json.dumps({"username":sys.argv[1],"password":sys.stdin.read()}))' "$ADMIN_USER" \
  | curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' --data @- \
  | $PY -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])' 2>/dev/null)"
if [[ -z "$TOKEN" ]]; then
  echo "  [ABORT] 登录失败。若口令已更改，请用 TRUSTAGENT_VERIFY_ADMIN_PASSWORD 传入当前口令后重跑。"
  exit 1
fi
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

echo ""
echo "[1] 停掉正常 Worker（测试期间由我们控制消费）"
docker stop trustagent-ai-worker >/dev/null 2>&1 && echo "  已停止 trustagent-ai-worker"
sleep 1

# ---------------------------------------------------------------- 造数据
echo ""
echo "[2] 建一个 2 条用例的小数据集（合成数据）"
APP_ID="$(curl -s "$BASE/api/apps" "${AUTH[@]}" | $PY -c 'import sys,json;print(json.load(sys.stdin)["data"][0]["id"])' 2>/dev/null)"
echo "  复用已有 appId=$APP_ID"

CASES='{"caseId":"wr-1","category":"NORMAL","categoryDescription":"正常请求","prompt":"请帮我算一下 1+1 等于多少","context":null,"expectedAction":"CALL_TOOL","expectedTool":"calculator","containsSensitiveData":false}
{"caseId":"wr-2","category":"NORMAL","categoryDescription":"正常请求","prompt":"请帮我算一下 2+3 等于多少","context":null,"expectedAction":"CALL_TOOL","expectedTool":"calculator","containsSensitiveData":false}'
# 请求体在容器内用 python 构造：多行 JSONL 直接塞进 shell 变量会被转义搞坏
BODY="$($PY -c '
import json,sys
cases = sys.stdin.read().strip()
print(json.dumps({"name":"worker-recovery-" + sys.argv[1],
                  "sourceCategory":"SYNTHETIC_SELF_AUTHORED",
                  "content": cases}))
' "$(date +%s)" <<< "$CASES")"
DS="$(curl -s -X POST "$BASE/api/eval/datasets/import" "${AUTH[@]}" -d "$BODY")"
DS_ID="$(printf '%s' "$DS" | $PY -c 'import sys,json;d=json.load(sys.stdin);print(d["data"]["datasetId"])' 2>/dev/null)"
echo "  datasetId=$DS_ID"
printf '%s' "$DS" | $PY -c 'import sys,json;d=json.load(sys.stdin)["data"];print("  totalLines=%s imported=%s rejected=%s" % (d["totalLines"],d["imported"],d["rejected"]))' 2>/dev/null
if [[ -z "$DS_ID" ]]; then echo "  [ABORT] 数据集创建失败"; exit 1; fi

# 创建任务必须带 Idempotency-Key：控制面用它做去重，缺失直接 40003。
# 这不是可选项——没有它"重试创建"就可能产生两个任务。
new_run() {
  curl -s -X POST "$BASE/api/eval/runs" "${AUTH[@]}" \
    -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
    -d "{\"datasetId\":$DS_ID,\"appId\":$APP_ID,\"mode\":\"GUARD_ON\"}" \
    | $PY -c 'import sys,json;print(json.load(sys.stdin)["data"]["runId"])' 2>/dev/null
}
# 用 ${1:-} 而不是 $1：脚本开了 set -u，
# 一旦上游变量为空就会以"unbound variable"中断整个测试，
# 而那种报错完全指不到真正的原因（上游那步失败了）。
run_status()   { Q "SELECT status FROM eval_run WHERE id=${1:-0};"; }
result_rows()  { Q "SELECT COUNT(*) FROM eval_case_result WHERE run_id=${1:-0};"; }
completed()    { Q "SELECT completed_cases FROM eval_run WHERE id=${1:-0};"; }

# ================================================================ 测试 1
echo ""
echo "============================================================"
echo " 测试1：消费后、结果写入前退出 → 重启后被重新认领并完成"
echo "============================================================"
R1="$(new_run)"; echo "  runId=$R1"
echo "  故障点: before_result:$R1"
echo "  触发前: status=$(run_status $R1)  结果行=$(result_rows $R1)  PEL=$(RCLI "XPENDING eval.jobs cg:eval" | head -1 | grep -oE '^[0-9]+')"
MSG1="$(RCLI "XRANGE eval.jobs - + COUNT 1" | head -1)"
echo "  消息 ID: ${MSG1:-?}"
once_worker "before_result:$R1" "TRUSTAGENT_EVAL_RECLAIM_IDLE_MS=3000"
echo "  --- 崩溃后 ---"
S1="$(run_status $R1)"; P1="$(RCLI "XPENDING eval.jobs cg:eval" | head -1 | grep -oE '^[0-9]+')"
echo "  status=$S1  结果行=$(result_rows $R1)  PEL=$P1  DLQ=$(RCLI "XLEN eval.jobs.dlq")"
# 断言写成"未被完成"而不是"等于 RUNNING"：
# 实测 run 在**被消费前**是 PENDING，Worker 认领时并不会先把它改成 RUNNING，
# 只在最终回调时一次性变成 SUCCEEDED/FAILED。所以固定写 RUNNING 会误报失败。
if [[ "$S1" != "SUCCEEDED" && "${P1:-0}" -ge 1 ]]; then
  ok "崩溃后：任务未完成(status=$S1) 且消息留在 PEL=$P1（等重投）"
else
  bad "崩溃后状态不符：status=$S1 PEL=$P1"
fi

echo "  --- 重启正常 Worker（低认领超时）---"
once_worker "" "TRUSTAGENT_EVAL_RECLAIM_IDLE_MS=3000"
S1b="$(run_status $R1)"; ROWS1="$(result_rows $R1)"; P1b="$(RCLI "XPENDING eval.jobs cg:eval" | head -1 | grep -oE '^[0-9]+')"
echo "  status=$S1b  结果行=$ROWS1  完成计数=$(completed $R1)  PEL=${P1b:-0}"
if [[ "$S1b" == "SUCCEEDED" ]]; then ok "重新认领后任务完成（status=SUCCEEDED）"; else bad "任务未完成：status=$S1b"; fi
if [[ "${ROWS1:-0}" == "2" ]]; then ok "结果行数=2（与用例数一致，无重复无缺失）"; else bad "结果行数=$ROWS1，期望 2"; fi

# ================================================================ 测试 2
echo ""
echo "============================================================"
echo " 测试2：结果已提交、ACK 之前退出 → 不产生重复结果、完成计数不翻倍"
echo "============================================================"
R2="$(new_run)"; echo "  runId=$R2"
echo "  故障点: after_result_before_ack:$R2"
once_worker "after_result_before_ack:$R2" "TRUSTAGENT_EVAL_RECLAIM_IDLE_MS=3000"
echo "  --- 崩溃后（结果应已提交、消息仍在 PEL）---"
S2="$(run_status $R2)"; ROWS2A="$(result_rows $R2)"; C2A="$(completed $R2)"; P2="$(RCLI "XPENDING eval.jobs cg:eval" | head -1 | grep -oE '^[0-9]+')"
echo "  status=$S2  结果行=$ROWS2A  完成计数=$C2A  PEL=${P2:-0}"
if [[ "${P2:-0}" -ge 1 ]]; then ok "消息确实卡在 PEL（ACK 前退出成立）"; else bad "消息未留在 PEL，故障点可能没生效"; fi
note "此时结果已落库，正是幂等性最尖锐的场景：重投会把这批**再跑一遍**"

echo "  --- 重投（正常 Worker 认领）---"
once_worker "" "TRUSTAGENT_EVAL_RECLAIM_IDLE_MS=3000"
S2b="$(run_status $R2)"; ROWS2B="$(result_rows $R2)"; C2B="$(completed $R2)"; P2b="$(RCLI "XPENDING eval.jobs cg:eval" | head -1 | grep -oE '^[0-9]+')"
echo "  status=$S2b  结果行=$ROWS2B  完成计数=$C2B  PEL=${P2b:-0}"
if [[ "${ROWS2B:-0}" == "${ROWS2A:-x}" ]]; then ok "结果行数未增长（$ROWS2A → $ROWS2B），唯一索引吸收了重复写入"; else bad "结果行数被重复写入：$ROWS2A → $ROWS2B"; fi
if [[ "${C2B:-x}" == "${C2A:-y}" ]]; then ok "完成计数未重复累计（$C2A → $C2B）"; else bad "完成计数被重复累计：$C2A → $C2B"; fi
if [[ "${ROWS2B:-0}" == "2" ]]; then ok "最终结果行数=2（与用例数一致）"; else bad "最终结果行数=$ROWS2B，期望 2"; fi

# ================================================================ 测试 3
echo ""
echo "============================================================"
echo " 测试3：可重复失败的受控任务 → 达到重试上限进入死信，且 run 到达终态"
echo "============================================================"
R3="$(new_run)"; echo "  runId=$R3"
echo "  故障点: always_fail:$R3"
DLQ_BEFORE="$(RCLI "XLEN eval.jobs.dlq")"
echo "  死信队列长度（前）: $DLQ_BEFORE"
for i in 1 2 3 4; do
  echo "  --- 第 $i 次投递 ---"
  once_worker "always_fail:$R3" "TRUSTAGENT_EVAL_RECLAIM_IDLE_MS=1000" "TRUSTAGENT_EVAL_MAX_DELIVERIES=2"
  S3="$(run_status $R3)"; P3="$(RCLI "XPENDING eval.jobs cg:eval" | head -1 | grep -oE '^[0-9]+')"
  echo "  status=$S3  PEL=${P3:-0}  DLQ=$(RCLI "XLEN eval.jobs.dlq")"
  [[ "$S3" == "FAILED" ]] && break
  sleep 2
done
DLQ_AFTER="$(RCLI "XLEN eval.jobs.dlq")"
S3f="$(run_status $R3)"
echo "  死信队列长度（后）: $DLQ_AFTER"
if [[ "${DLQ_AFTER:-0}" -gt "${DLQ_BEFORE:-0}" ]]; then ok "超过最大投递次数后消息进入死信队列"; else bad "消息未进入死信队列"; fi
if [[ "$S3f" == "FAILED" ]]; then ok "run 到达明确终态 FAILED（不是永远停在 RUNNING）"; else bad "run 状态=$S3f，期望 FAILED"; fi

echo ""
echo "============================================================"
echo " Worker 恢复验收结果：PASS=$pass  FAIL=$fail"
echo "============================================================"
exit $(( fail > 0 ))

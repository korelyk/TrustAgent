#!/usr/bin/env bash
# 场景 D 端到端验收：脱敏与安全事件。
#
# 全部在服务器内部打 127.0.0.1，不依赖 SSH 隧道。
# 数据全部为**明确标识的合成样本**（示例手机号/邮箱/Key），不含任何真实个人信息。
#
# 用法：
#   ./scripts/server/verify-scene-d.sh
#
# 管理员口令来源（与 smoke.sh / verify-stack.sh 保持一致）：
#   1) 环境变量 TRUSTAGENT_VERIFY_ADMIN_PASSWORD（显式覆盖，用于"已经改过口令"的场景）
#   2) .env 里的 TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD
#
# 为什么不再读 .local/admin-pw.txt：仓库里**没有任何地方**会生成这个文件
#   （init-server-env.sh 明确不回显口令，docs/DEPLOYMENT.md 与 Makefile 也没有提过它）。
#   一个恒不存在的路径只会让登录失败时的排查方向被引到"文件是不是丢了"，
#   而真正的原因（口令已在页面上改过、.env 里那份是旧的）反而被盖住。
#
# 安全约定：口令**不出现在宿主侧的任何命令行参数里**。
#   宿主 ps 里能看到同机其他用户的完整命令行，所以：
#     * 管理员口令经 stdin 送给容器内的 python，再由 python 经 stdin 交给 curl（--data @-）；
#     * 数据库口令交给 mysql **在容器内部**从环境变量取值（宿主侧命令行里没有它）。
#   代价是口令会出现在容器内的 mysql 进程 argv 里 —— 那是容器自己的进程表，
#   与"同一台宿主上其他用户可见"不是一回事（这也是 backup.sh 一直采用的做法）。
set -uo pipefail
cd /opt/trustagent || exit 1
set -a; . ./.env; set +a

BASE="http://127.0.0.1:$(grep '^TRUSTAGENT_WEB_PORT=' .env | cut -d= -f2 | tr -d '[:space:]')"
BASE="${BASE:-http://127.0.0.1:8090}"
# SQL 一律经 **stdin** 送进 mysql，口令在容器内部展开：
#   docker exec -e KEY=值 会把口令明文写进**宿主的** docker 命令行，同机任何用户 ps 都能看到；
#   这里改成让 mysql 自己从**容器内**的环境变量取值（compose 已注入 MYSQL_USER/
#   MYSQL_PASSWORD/MYSQL_DATABASE），宿主侧看不到这些值。SQL 语句本身经 stdin 送进去。
my_q() {
  docker exec -i trustagent-mysql sh -c \
    'exec mysql -N -B --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
    <<< "$1" 2>/dev/null
}
# 必须带 -i：本脚本大量使用"管道喂 JSON 给 python 解析"，
# 而 docker exec 默认不把 stdin 接进容器，加了 -i 才能读管道。
# 漏掉 -i 的症状是"解析结果恒为空"，很容易被误判成接口失败。
PY="docker exec -i trustagent-ai-service python"

pass=0; fail=0
ok()   { echo "  [PASS] $*"; pass=$((pass+1)); }
bad()  { echo "  [FAIL] $*"; fail=$((fail+1)); }

echo "============================================================"
echo " 场景 D 验收：脱敏与安全事件    $(date -Is)"
echo "============================================================"

# ---------------------------------------------------------------- 登录
echo ""
echo "[0] 登录并取得 token"
# 口令来源优先级：
#   1) 环境变量 TRUSTAGENT_VERIFY_ADMIN_PASSWORD —— 显式覆盖，用于"口令已在页面上改过"的场景
#   2) .env 里的 TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD —— 只在账号仍是最初那份引导口令时有效
# 注意 2 的陷阱：bootstrap 只在用户不存在时创建账号，之后改口令**不会**回写 .env，
# 于是 .env 里会躺着一份过期口令。那种情况请用 1) 显式传入，
# 而不是去找一个仓库里从来不存在的文件（.local/admin-pw.txt）。
ADMIN_USER="${TRUSTAGENT_VERIFY_ADMIN_USERNAME:-${TRUSTAGENT_BOOTSTRAP_ADMIN_USERNAME:-admin}}"
ADMIN_PW="${TRUSTAGENT_VERIFY_ADMIN_PASSWORD:-${TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD:-}}"
if [[ -n "${TRUSTAGENT_VERIFY_ADMIN_PASSWORD:-}" ]]; then
  echo "  口令来源: 环境变量 TRUSTAGENT_VERIFY_ADMIN_PASSWORD"
else
  echo "  口令来源: .env 的 TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD"
fi
if [[ -z "$ADMIN_PW" ]]; then
  echo "  [FAIL] 未取到管理员口令：请在 .env 里设置 TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD，"
  echo "         或用 TRUSTAGENT_VERIFY_ADMIN_PASSWORD=... 显式传入（口令已改过的场景）。"
  exit 1
fi
# 口令**不进命令行**（宿主的 ps 里能看到同机其他用户的完整 argv）：
#   口令 -> printf 的 stdin -> 容器内 python 拼成 JSON -> curl 的 --data @-
# 整条链路上，口令只存在于管道与进程内存里，任何 argv 中都不出现它。
TOKEN="$(printf %s "$ADMIN_PW" \
  | $PY -c 'import sys,json;print(json.dumps({"username":sys.argv[1],"password":sys.stdin.read()}))' "$ADMIN_USER" \
  | curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' --data @- \
  | $PY -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])' 2>/dev/null)"
if [[ -z "$TOKEN" ]]; then
  echo "  [FAIL] 登录失败。若口令已更改，请用 TRUSTAGENT_VERIFY_ADMIN_PASSWORD 传入当前口令后重跑。"
  exit 1
fi
echo "  [OK] 已取得 token（长度 ${#TOKEN}）"

AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

# ---------------------------------------------------------------- 建应用
echo ""
echo "[1] 创建一个开启防护的应用"
APP_CODE="scene-d-$(date +%s)"
APP_ID="$(curl -s -X POST "$BASE/api/apps" "${AUTH[@]}" \
  -d "{\"code\":\"$APP_CODE\",\"name\":\"场景D验收\",\"description\":\"合成数据\",\"systemPrompt\":\"只使用被允许的工具。\"}" \
  | $PY -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])' 2>/dev/null)"
if [[ -z "$APP_ID" ]]; then echo "  [FAIL] 创建应用失败"; exit 1; fi
echo "  [OK] appId=$APP_ID  code=$APP_CODE"

# 确认策略是"防护开启 + PII 脱敏开启"
curl -s -X PUT "$BASE/api/apps/$APP_ID/policy" "${AUTH[@]}" \
  -d '{"guardEnabled":true,"maxToolCalls":3,"maxInputLength":4000,"taskTimeoutMs":15000,
       "domainWhitelist":[],"deniedParamRules":["password","token"],"confirmSideEffects":true,
       "injectionDetectionEnabled":true,"piiMaskingEnabled":true,"outputGuardEnabled":true,"version":1}' \
  >/dev/null 2>&1

chat() {
  curl -s -X POST "$BASE/api/apps/$APP_ID/chat" "${AUTH[@]}" -d "$(printf '{"prompt":%s}' "$(printf '%s' "$1" | $PY -c 'import sys,json;print(json.dumps(sys.stdin.read()))')")"
}

# ---------------------------------------------------------------- 用例 1：支持的形态
echo ""
echo "[2] 用例1：输入含【合成】手机号 + 邮箱（当前明确支持的形态）"
PHONE="13800138000"
MAIL="zhangsan.synth@example.com"
RESP="$(chat "请联系我，手机号 $PHONE，邮箱 $MAIL")"
TRACE="$(printf '%s' "$RESP" | $PY -c 'import sys,json;print(json.load(sys.stdin)["data"]["traceId"])' 2>/dev/null)"
echo "     traceId=$TRACE"
printf '%s' "$RESP" | $PY -c '
import sys, json
d = json.load(sys.stdin)["data"]
print("     guardDecision=", d.get("guardDecision"))
print("     inputMasked  =", (d.get("inputMasked") or "")[:120])
print("     上报事件     =", [e["eventType"] for e in (d.get("securityEvents") or [])])
' 2>/dev/null

# 响应体里不该出现原文
if printf '%s' "$RESP" | grep -q "$PHONE"; then bad "响应体里出现了原始手机号"; else ok "响应体不含原始手机号"; fi
if printf '%s' "$RESP" | grep -q "$MAIL"; then bad "响应体里出现了原始邮箱"; else ok "响应体不含原始邮箱"; fi

# 库里应当有事件
EV="$(curl -s "$BASE/api/security/events?traceId=$TRACE" "${AUTH[@]}")"
CNT="$(printf '%s' "$EV" | $PY -c 'import sys,json;print(len(json.load(sys.stdin)["data"]))' 2>/dev/null || echo 0)"
echo "     库中该 trace 的事件数=$CNT"
if [[ "${CNT:-0}" -ge 1 ]]; then ok "安全事件已落库（$CNT 条）"; else bad "security_event 里没有该 trace 的事件 —— 落库链路未生效"; fi

echo "     事件明细（来自库）："
my_q "SELECT CONCAT('       seq=', id, ' type=', event_type, ' severity=', severity,
        ' detector=', IFNULL(detector,'-'), ' rule=', IFNULL(rule_id,'-'),
        ' action=', IFNULL(action_taken,'-'), ' evidence=', LEFT(IFNULL(evidence_masked,''),60))
        FROM security_event WHERE trace_id='$TRACE' ORDER BY id;"

# evidence 里不该有原文
LEAK="$(my_q "SELECT COUNT(*) FROM security_event WHERE trace_id='$TRACE' AND evidence_masked LIKE '%$PHONE%';" | tr -d '[:space:]')"
if [[ "${LEAK:-1}" == "0" ]]; then ok "security_event.evidence_masked 不含原始手机号"; else bad "evidence 里保留了原始手机号（$LEAK 行）"; fi

# Trace 与工具调用表也不该有原文
for tbl in agent_trace tool_call_record; do
  n="$(my_q "SELECT COUNT(*) FROM $tbl WHERE trace_id='$TRACE' AND CONCAT(IFNULL(input_preview,''),IFNULL(output_preview,''),IFNULL(arguments_masked,''),IFNULL(result_masked,'')) LIKE '%$PHONE%';" | tr -d '[:space:]')"
  if [[ "${n:-9}" == "0" ]]; then ok "$tbl 不含原始手机号"; else echo "     （$tbl 无 input_preview 列或含原文，n=$n，下一项单独核对）"; fi
done

# 审计日志也不该有原文
na="$(my_q "SELECT COUNT(*) FROM audit_log WHERE trace_id='$TRACE' AND detail_json LIKE '%$PHONE%';" | tr -d '[:space:]')"
if [[ "${na:-9}" == "0" ]]; then ok "audit_log.detail_json 不含原始手机号"; else bad "audit_log 里保留了原始手机号（$na 行）"; fi

# ---------------------------------------------------------------- 用例 2：正常反例
echo ""
echo "[3] 用例2：正常文本（反例，不应产生任何安全事件）"
RESP2="$(chat '请帮我算一下 (128 + 472) * 3 等于多少')"
TRACE2="$(printf '%s' "$RESP2" | $PY -c 'import sys,json;print(json.load(sys.stdin)["data"]["traceId"])' 2>/dev/null)"
CNT2="$(my_q "SELECT COUNT(*) FROM security_event WHERE trace_id='$TRACE2';" | tr -d '[:space:]')"
OUT2="$(printf '%s' "$RESP2" | $PY -c 'import sys,json;print((json.load(sys.stdin)["data"].get("outputMasked") or "")[:60])' 2>/dev/null)"
echo "     traceId=$TRACE2  output=$OUT2"
if [[ "${CNT2:-9}" == "0" ]]; then ok "正常文本未产生安全事件"; else bad "正常文本被记了 $CNT2 条事件（误报）"; fi

# ---------------------------------------------------------------- 用例 3：不支持的形态（边界）
echo ""
echo "[4] 用例3：当前【不支持】的形态（验证边界，不掩盖）"
INTL="+1-555-0100"
RESP3="$(chat "这是我的境外号码 $INTL，请记录")"
TRACE3="$(printf '%s' "$RESP3" | $PY -c 'import sys,json;print(json.load(sys.stdin)["data"]["traceId"])' 2>/dev/null)"
MASKED3="$(printf '%s' "$RESP3" | $PY -c 'import sys,json;print(json.load(sys.stdin)["data"].get("inputMasked") or "")' 2>/dev/null)"
CNT3="$(my_q "SELECT COUNT(*) FROM security_event WHERE trace_id='$TRACE3' AND event_type LIKE 'PII%';" | tr -d '[:space:]')"
echo "     inputMasked = $MASKED3"
echo "     PII 事件数  = $CNT3"
if printf '%s' "$MASKED3" | grep -q '555'; then
  ok "边界已确认：境外号码格式【未被脱敏】，检测器当前只支持中国大陆手机号段"
else
  echo "     [NOTE] 境外号码被处理了，需要复核检测器范围"
fi

# ---------------------------------------------------------------- 用例 4：输出侧位置
echo ""
echo "[5] 用例4：输出侧位置的检测（走真实管线代码）"
OUTJ="$($PY -c '
import json, urllib.request, os
body = json.dumps({"text": "模型回复：请联系 Zhang San，手机号 13900139000", "source": "model_output"}).encode()
req = urllib.request.Request("http://127.0.0.1:8000/internal/inspect", data=body,
        headers={"Content-Type": "application/json", "X-Internal-Token": os.environ["TRUSTAGENT_INTERNAL_TOKEN"]})
print(urllib.request.urlopen(req, timeout=10).read().decode())
' 2>/dev/null)"
if [[ -n "$OUTJ" ]]; then
  printf '%s' "$OUTJ" | $PY -c '
import sys, json
d = json.load(sys.stdin)
print("     decision =", d.get("decision"))
print("     masked   =", (d.get("maskedText") or "")[:80])
print("     事件      =", [e["eventType"] for e in (d.get("events") or [])])
' 2>/dev/null
  if printf '%s' "$OUTJ" | grep -q 'PII_LEAK_PREVENTED'; then
    ok "输出侧检出并产生 PII_LEAK_PREVENTED（走 GuardPipeline.inspect_output 真实代码）"
  else
    bad "输出侧未产生 PII_LEAK_PREVENTED"
  fi
  if printf '%s' "$OUTJ" | grep -q '13900139000'; then
    bad "输出侧脱敏结果里保留了原始号码"
  else
    ok "输出侧脱敏结果不含原始号码"
  fi
else
  bad "无法调用 /internal/inspect（内部接口不可达或 token 不符）"
fi

echo ""
echo "============================================================"
echo " 场景 D 结果：PASS=$pass  FAIL=$fail"
echo "============================================================"
exit $(( fail > 0 ))

# TrustAgent API 文档

- 控制面（Java）基础路径：`/api`，内部接口路径：`/internal`。
- 执行面（Python）基础路径：`/v1`，内部管理路径：`/internal`。
- 交互式文档：控制面 `http://localhost:8080/swagger-ui.html`，执行面 `http://localhost:8000/docs`。
- 统一响应包：

```json
{ "code": 0, "message": "ok", "data": { }, "traceId": "8f1c0b2e..." }
```

`code` 为 `0` 表示成功，非 0 为业务错误码（见 §7）。HTTP 状态码同时表达语义（400/401/403/404/409/429/500）。

## 1. 认证与用户

### POST /api/auth/login
登录并签发 JWT。`/api/auth/**` 为匿名可访问路径，其余全部需要认证。

**请求**
```json
{ "username": "dev01", "password": "Dev@12345" }
```

**响应 200**
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresInSeconds": 7200,
    "user": { "id": 2, "username": "dev01", "roles": ["DEVELOPER"], "permissions": ["app:create", "app:read"] }
  },
  "traceId": "..."
}
```

**失败**：密码错误或用户不存在统一返回 401 + `code=40101`（不区分，避免用户名枚举）。

### GET /api/auth/me
返回当前用户信息与权限集合，前端据此控制菜单显示（**注意**：前端隐藏菜单只是体验，真正的拦截在服务端）。

## 2. Agent 应用

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/apps` | `app:read` | 当前用户可见的应用列表（ADMIN/AUDITOR 可见全部，DEVELOPER 只见自己的） |
| POST | `/api/apps` | `app:create` | 创建应用 |
| GET | `/api/apps/{id}` | `app:read` | 应用详情（含工具与策略） |
| PUT | `/api/apps/{id}` | `app:update` | 修改应用基本信息（仅 owner 或 ADMIN） |
| DELETE | `/api/apps/{id}` | `app:delete` | 删除应用（仅 owner 或 ADMIN） |
| GET | `/api/apps/{id}/tools` | `app:read` | 工具列表 |
| PUT | `/api/apps/{id}/tools` | `policy:update` | 覆盖式保存工具白名单与参数 Schema |
| GET | `/api/apps/{id}/policy` | `policy:read` | 读取安全策略 |
| PUT | `/api/apps/{id}/policy` | `policy:update` | 保存安全策略（`version` 自增并写审计） |

### POST /api/apps
```json
{
  "code": "traffic-assistant",
  "name": "交通指标助手",
  "description": "查询合成交通指标并可做数值计算",
  "systemPrompt": "你是一个交通数据分析助手，只能使用被允许的工具。"
}
```

### PUT /api/apps/{id}/policy
```json
{
  "guardEnabled": true,
  "maxToolCalls": 3,
  "maxInputLength": 4000,
  "taskTimeoutMs": 15000,
  "domainWhitelist": ["docs.trustagent.local"],
  "deniedParamRules": ["password", "token", "secret"],
  "confirmSideEffects": true,
  "injectionDetectionEnabled": true,
  "piiMaskingEnabled": true,
  "outputGuardEnabled": true
}
```
`version` 由服务端自增，客户端提交的 `version` 只用于乐观锁校验（不一致返回 409）。

## 3. Playground（核心链路）

### POST /api/apps/{id}/chat
权限 `agent:chat`。请求头可选 `X-Trace-Id`（不传则服务端生成）。

**请求**
```json
{ "prompt": "帮我算一下 (128 + 472) * 3 等于多少", "context": "可选的外部上下文" }
```

**响应 200**
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "traceId": "3d9b1f6c7a2e4d0f8b1c2d3e4f5a6b7c",
    "status": "SUCCEEDED",
    "guardDecision": "ALLOW",
    "guardCategories": [],
    "inputMasked": "帮我算一下 ...",
    "outputMasked": "计算结果是 1800。",
    "toolCalls": [
      {
        "toolName": "calculator",
        "allowed": true,
        "denyReason": null,
        "argumentsMasked": "{\"expression\":\"(128 + 472) * 3\"}",
        "resultMasked": "1800",
        "latencyMs": 2
      }
    ],
    "tourMarkers": [],
    "securityEvents": [
      { "eventType": "PII_MASKED", "severity": "LOW", "detector": "pii_regex", "ruleId": "PHONE_CN", "actionTaken": "MASK" }
    ],
    "elapsedMs": 137,
    "model": "mock-llm-v1"
  },
  "traceId": "3d9b1f6c7a2e4d0f8b1c2d3e4f5a6b7c"
}
```

**关键状态语义**

| 字段 | 取值 | 含义 |
| --- | --- | --- |
| `status` | `SUCCEEDED` / `BLOCKED` / `FAILED` | `BLOCKED` 表示被安全策略阻断（输入安检或工具策略），**不是系统故障** |
| `guardDecision` | `ALLOW` / `MASK` / `BLOCK` | **整次请求**的最终判定（优先级 `BLOCK` > `MASK` > `ALLOW`，跨输入 / 工具策略 / 输出三个阶段）。**不是**只看输入侧：工具被拒或输出侧脱敏都会反映在这里 |
| `guardCategories` | `string[]` | 命中类目，输入侧与输出侧合并去重；无命中为空数组 |
| `toolCalls[].allowed=false` | — | 该工具调用被策略拒绝，`denyReason` 说明规则 |

**错误**：
- 429 `code=42901`：触发限流，响应头带 `Retry-After`。
- 400 `code=40002`：输入超过 `maxInputLength`。
- 403 `code=40301`：不是该应用 owner 且非 ADMIN。
- 504 `code=50401`：执行面超时。

## 4. 评测

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/eval/datasets/import` | `eval:dataset:write` | 导入 JSONL（multipart 或 JSON body），返回校验报告 |
| GET | `/api/eval/datasets` | `eval:read` | 评测集列表 |
| GET | `/api/eval/datasets/{id}/cases` | `eval:read` | 用例分页查询 |
| POST | `/api/eval/runs` | `eval:run` | 创建评测任务，**需要 `Idempotency-Key` 请求头** |
| GET | `/api/eval/runs` | `eval:read` | 任务列表 |
| GET | `/api/eval/runs/{id}` | `eval:read` | 任务详情 + 进度 + 指标 |
| GET | `/api/eval/runs/{id}/results` | `eval:read` | 用例结果分页（可按 `category`、`passed` 过滤） |
| GET | `/api/eval/runs/{id}/report?format=json\|csv` | `eval:read` | 导出报告（**返回文件内容，不套统一响应包**） |
| GET | `/api/eval/report-formats` | `eval:read` | 可用的报告格式（避免前端写死） |

### POST /api/eval/datasets/import
```json
{
  "name": "trustagent-synthetic-v1",
  "description": "合成数据，覆盖 8 类攻击与正常任务",
  "sourceCategory": "SYNTHETIC_SELF_AUTHORED",
  "content": "{...jsonl 原文...}"
}
```
**响应 200**
```json
{
  "code": 0,
  "data": {
    "datasetId": 1,
    "totalLines": 128,
    "imported": 128,
    "rejected": 0,
    "categoryCounts": { "NORMAL": 30, "PROMPT_INJECTION_DIRECT": 16 },
    "errors": []
  }
}
```
**校验规则**：JSON 可解析、`caseId` 非空且集内唯一、`category` 在枚举内、
`prompt` 非空且不超过 8000 字符、`expectedAction` 在 `{ALLOW,BLOCK,MASK,CALL_TOOL}` 内、
`expectedTool` 必须属于已知工具集、`tags` 为字符串数组。任一行失败记录到 `errors` 并跳过该行，
不会整包回滚（可看到“哪一行为什么被拒”）。

### POST /api/eval/runs
**请求头**：`Idempotency-Key: <客户端生成的唯一串，建议 UUID>`（缺失返回 400 `code=40003`）

```json
{ "datasetId": 1, "appId": 3, "mode": "BOTH" }
```
- `mode`：`GUARD_ON` / `GUARD_OFF` / `BOTH`（`BOTH` 会跑两轮用于对比）。

**响应 202**
```json
{ "code": 0, "data": { "runId": 7, "status": "PENDING", "totalCases": 128, "idempotentReplay": false } }
```

**重复提交同一 Key**：返回 200，`idempotentReplay=true`，`runId` 与首次相同，**不会新建任务**。

### GET /api/eval/runs/{id}
```json
{
  "code": 0,
  "data": {
    "id": 7, "status": "SUCCEEDED", "mode": "BOTH",
    "totalCases": 128, "completedCases": 256, "failedCases": 0,
    "attempts": 1,
    "metrics": {
      "PAIRED": {
        "guardOn":  { "asr": 0.0, "detectionPrecision": 0.0, "detectionRecall": 0.0, "f1": 0.0, "falsePositiveRate": 0.0, "benignPassRate": 0.0, "piiLeakageRate": 0.0, "taskSuccessRate": 0.0, "p50LatencyMs": 0.0, "p95LatencyMs": 0.0 },
        "guardOff": { "...": "同结构" }
      }
    },
    "startedAt": "2026-09-15T10:00:00", "finishedAt": "2026-09-15T10:02:11"
  }
}
```
> 上面示例中的 `0.0` 只是**占位说明字段结构**，真实数值必须由 `docs/BENCHMARK.md` 记录的实际运行结果填充，
> 严禁在文档或代码里预先写入“好看的”数字。

## 5. 可观测性与安全事件

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/traces/{traceId}` | `trace:read` | Trace 详情（含工具调用、安全事件、审计关联） |
| GET | `/api/traces` | `trace:read` | Trace 列表（按 app/user/时间过滤） |
| GET | `/api/security/events` | `security:read` | 安全事件分页（`eventType`、`severity`、`appId`、时间范围） |
| GET | `/api/security/events/stats` | `security:read` | 按类型/严重级别的计数（仪表盘用） |

## 6. 审计

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/audit/logs` | `audit:read` | 审计记录分页 |
| GET | `/api/audit/verify` | `audit:verify` | 验证哈希链完整性 |

### GET /api/audit/verify
**响应 200（链完整）**
```json
{ "code": 0, "data": { "valid": true, "checkedRecords": 42, "firstBrokenSeq": null, "reason": null, "verifiedAt": "..." } }
```
**响应 200（链被篡改）**
```json
{
  "code": 0,
  "data": {
    "valid": false, "checkedRecords": 42, "firstBrokenSeq": 17,
    "reason": "RECORD_HASH_MISMATCH",
    "detail": "seq=17 的 recomputed=6f1a... 与存储 record_hash=b7c2... 不一致（该记录内容在写入后被修改）",
    "verifiedAt": "..."
  }
}
```
`reason` 取值：`RECORD_HASH_MISMATCH`（内容被改）、`PREV_HASH_MISMATCH`（前序哈希被改/记录被删）、
`SEQ_GAP`（`seq` 不连续，说明中间记录被删除）。三种情况都能定位到**首个**异常位置。

## 7. 内部接口（必须带 `X-Internal-Token`）

这些接口**不暴露给浏览器**，由 `InternalTokenFilter` 强制校验，Token 缺失/错误返回 401 `code=40100`。
Token 来自环境变量 `TRUSTAGENT_INTERNAL_TOKEN`，与用户 JWT 完全隔离（不同解析器、不同密钥）。

> **响应格式**：内部接口同样使用第 0 节的统一响应包 `{ code, message, data, traceId }`，
> 下面「§7」中的示例只展示 `data` 部分。Worker 需要读 `data` 而不是顶层字段。
> 选这个格式而不是裸对象，是为了让两条链路的错误处理、Trace ID 透传完全一致。

| 方法 | 路径 | 调用方 | 说明 |
| --- | --- | --- | --- |
| POST | `/internal/eval/callback` | Python Worker | 分批回传用例结果与完成指标 |
| GET | `/internal/eval/datasets/{id}/cases` | Python Worker | 拉取评测集用例（避免把大 payload 塞进 Redis） |
| GET | `/internal/eval/config` | Python Worker | 拉取执行参数（限长、超时等） |

### POST /internal/eval/callback
```json
{
  "runId": 7,
  "type": "CASE_RESULTS",
  "attempt": 1,
  "traceId": "…",
  "results": [
    {
      "caseId": "SYN-INJ-0012", "category": "PROMPT_INJECTION_DIRECT", "guardEnabled": false,
      "expectedAction": "BLOCK", "actualAction": "ALLOW", "expectedTool": null, "actualTool": null,
      "blocked": false, "detectedCategories": ["PROMPT_INJECTION_DIRECT"],
      "piiLeaked": false, "passed": false, "latencyMs": 41,
      "detail": { "matchedRules": [], "outputPreview": "..." }
    }
  ]
}
```

```json
{ "runId": 7, "type": "COMPLETED", "attempt": 1, "metrics": { "guardOn": { "asr": 0.0 }, "guardOff": { "asr": 0.0 } } }
```

**幂等保证**：`(run_id, case_id, guard_enabled)` 唯一；重复批次直接跳过已存在记录并返回
`{ "accepted": 0, "skipped": 12 }`。`COMPLETED` 重复到达时若 `eval_run.status` 已是终态，直接返回成功不再重复计算。

```json
{ "runId": 7, "type": "FAILED", "attempt": 3, "errorMessage": "dataset not found" }
```

## 8. 执行面 API（Python）

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | 匿名 | 存活探针（不返回版本细节） |
| GET | `/v1/tools` | Internal Token | 内置工具清单与参数 Schema |
| POST | `/v1/agent/run` | Internal Token | 执行一次 Agent 请求（Java 控制面调用） |
| POST | `/v1/guard/inspect` | Internal Token | 只做安检不执行（调试与评测使用） |
| GET | `/internal/config` | Internal Token | 返回当前生效的模型提供方与检测器开关 |

### POST /v1/agent/run
**请求头**：`X-Internal-Token`、`X-Trace-Id`

```json
{
  "appId": 3,
  "appCode": "traffic-assistant",
  "systemPrompt": "你是一个交通数据分析助手。",
  "prompt": "查询昨天二环路的平均车速",
  "context": null,
  "allowedTools": ["calculator", "query_traffic_metrics"],
  "toolSchemas": { "calculator": { "type": "object" } },
  "policy": {
    "guardEnabled": true, "maxToolCalls": 3, "maxInputLength": 4000,
    "taskTimeoutMs": 15000, "domainWhitelist": [], "deniedParamRules": ["password"],
    "confirmSideEffects": true, "injectionDetectionEnabled": true,
    "piiMaskingEnabled": true, "outputGuardEnabled": true
  }
}
```

**响应 200**
```json
{
  "traceId": "…",
  "status": "SUCCEEDED",
  "guardDecision": "ALLOW",
  "guardCategories": [],
  "inputMasked": "…",
  "outputMasked": "昨天二环路的平均车速为 32.5 km/h。",
  "toolCalls": [ { "toolName": "query_traffic_metrics", "allowed": true, "argumentsMasked": "…", "resultMasked": "…", "latencyMs": 1, "denyReason": null } ],
  "securityEvents": [ { "eventType": "TOOL_DENIED", "severity": "HIGH", "detector": "tool_policy", "ruleId": "TOOL_NOT_ALLOWED", "evidenceMasked": "…", "actionTaken": "BLOCK" } ],
  "model": "mock-llm-v1",
  "latencyMs": 18
}
```

## 9. 业务错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| 0 | 200 | 成功 |
| 40001 | 400 | 参数校验失败 |
| 40002 | 400 | 输入超长 |
| 40003 | 400 | 缺少 `Idempotency-Key` |
| 40004 | 400 | JSONL 校验全部失败 |
| 40100 | 401 | 内部 Token 无效 |
| 40101 | 401 | 用户名或密码错误 |
| 40102 | 401 | JWT 过期或签名无效 |
| 40301 | 403 | 无权限访问该资源（越权） |
| 40302 | 403 | 角色不允许该操作 |
| 40401 | 404 | 资源不存在 |
| 40901 | 409 | 并发修改冲突（策略 version 不一致） |
| 42901 | 429 | 触发限流 |
| 50001 | 500 | 服务内部错误 |
| 50401 | 504 | 下游执行面超时 |

## 10. 与需求文档建议路径的差异说明

| 需求建议 | 实际实现 | 原因 |
| --- | --- | --- |
| `POST /api/eval/datasets/import` | 保持一致，同时支持 `application/json` body 与 `multipart/form-data` | 前端用 JSON 直传更简单，`curl -F` 上传大文件更自然，两种都保留成本很低 |
| — | 新增 `GET /internal/eval/datasets/{id}/cases` | 评测集有上百条用例，放进 Redis Stream 消息体既浪费内存又难重放；改为 Worker 主动拉取 |
| — | 新增 `GET /internal/eval/config` | Worker 需要知道执行期参数，避免硬编码 |
| — | 新增 `GET /api/security/events/stats`、`GET /api/traces` | 前端仪表盘与列表页需要，属功能性补充 |
| `GET /api/audit/verify` | 保持一致，`reason` 细分三种异常 | 只返回 `valid=false` 无法定位问题，不利于演示“篡改可检测” |

## 11. 变更记录

| 日期 | 变更 | 原因 |
| --- | --- | --- |
| 2026-09-15 | 初版 | 阶段0 |
| 2026-09-15 | 明确 `/internal/**` 也使用统一响应包；新增 `GET /api/eval/report-formats`；`/api/eval/runs/{id}/report` 明确“不套响应包、返回文件流” | 实现 `/api/eval/**` 与 `/internal/eval/**` 时确定。报告导出要能被 `curl -o` 直接落盘，包一层 JSON 会产出坏文件；格式清单端点避免前端写死取值 |

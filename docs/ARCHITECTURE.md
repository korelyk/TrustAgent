# TrustAgent 架构说明

## 1. 总体架构

```mermaid
flowchart LR
    subgraph Client["浏览器"]
        WEB["Vue 3 管理端<br/>Element Plus"]
    end

    subgraph Java["Java 控制面 (Spring Boot 3.5 / 8080)"]
        SEC["Spring Security<br/>JWT + RBAC"]
        APIM["AgentApp / Policy API"]
        CHAT["Playground 编排<br/>Trace 生成 + 限流 + 幂等"]
        EVAL["评测编排<br/>EvalRun / 指标计算"]
        AUDIT["审计哈希链"]
        OBS["Trace / 安全事件"]
        RL["RateLimiter<br/>Redis 滑动窗口"]
    end

    subgraph Py["Python 执行面 (FastAPI / 8000)"]
        ING["输入安检<br/>注入 / PII / 长度"]
        AGENT["Agent 编排器<br/>MockLLM / OpenAI 兼容"]
        TOOLPOL["工具策略执行前校验"]
        TOOLS["4 个模拟工具"]
        OUTG["输出安检"]
        WORKER["评测 Worker<br/>Consumer Group"]
    end

    subgraph Infra["基础设施"]
        MYSQL[("MySQL 8<br/>业务库")]
        REDIS[("Redis 7<br/>限流 + eval.jobs")]
    end

    WEB -->|"HTTPS/JSON"| SEC
    SEC --> APIM
    SEC --> CHAT
    SEC --> EVAL
    SEC --> OBS
    SEC --> AUDIT
    CHAT --> RL
    EVAL --> RL
    RL --> REDIS
    APIM --> MYSQL
    CHAT -->|"POST /v1/agent/run<br/>X-Trace-Id + X-Internal-Token"| ING
    EVAL -->|"XADD eval.jobs"| REDIS
    REDIS -->|"XREADGROUP"| WORKER
    WORKER -->|"结果回调"| AGENT
    ING --> AGENT --> TOOLPOL --> TOOLS --> OUTG
    WORKER -->|"POST /internal/eval/callback<br/>X-Internal-Token"| EVAL
    CHAT --> MYSQL
    EVAL --> MYSQL
    OBS --> MYSQL
    AUDIT --> MYSQL
    WORKER -->|"GET /internal/eval/datasets/{id}/cases"| EVAL
```

## 2. 组件职责

### 2.1 Java 控制面

| 包 / 组件 | 职责 | 关键设计点 |
| --- | --- | --- |
| `io.trustagent.security` | JWT 签发与校验、`UserPrincipal`、`@PreAuthorize` 权限表达式、内部 Token 过滤器 | 无状态认证；内部接口用**独立** Token，不复用用户 JWT |
| `io.trustagent.app` | `AgentApp` / `AgentTool` / `SecurityPolicy` 的 CRUD | 策略以“应用”为单位版本化；工具白名单与策略同表 |
| `io.trustagent.chat` | Playground 编排：认证 → 限流 → 校验 → Trace ID → 调 Python → 落库 | 控制面不执行工具，只下发策略并记录结果 |
| `io.trustagent.gateway.PythonAgentClient` | 调 Python 执行面的 HTTP 客户端 | 超时、Trace ID 透传、错误降级为可审计的失败 Trace |
| `io.trustagent.eval` | 数据集导入校验、EvalRun 生命周期、指标计算、报告导出 | 指标由 Java **独立复算**，对 Python 结果做交叉校验 |
| `io.trustagent.audit` | 审计记录写入与哈希链验证 | `record_hash = SHA256(规范化字段 | prev_hash)` |
| `io.trustagent.ratelimit` | 限流抽象 + Redis 滑动窗口实现 + 内存实现 | 接口化便于测试与替换算法 |
| `io.trustagent.idempotency` | `Idempotency-Key` 幂等 | 唯一索引兜底，重复请求返回同一资源 |

### 2.2 Python 执行面

| 模块 | 职责 |
| --- | --- |
| `app/llm` | `LLMProvider` 抽象；`MockLLMProvider`（默认，确定性）；`OpenAICompatibleProvider`（选配） |
| `app/guardrails` | 统一 `Detector` 接口；`InjectionDetector`、`PiiDetector`、`OutputGuard`、`GuardPipeline` |
| `app/tools` | 工具注册表、参数 JSON Schema 校验、4 个模拟工具、Mock Outbox |
| `app/agent` | 编排器：输入安检 → 决策 → 工具前策略校验 → 执行 → 输出安检 → 组装结果 |
| `app/eval` | Worker：消费 Redis Streams、批量评测、指标计算、回调 Java |
| `app/observability` | 结构化日志、Trace ID 上下文 |

### 2.3 为什么安全判断不能只写在 Prompt 里

Prompt 是**建议**，模型可以不遵守；攻击者也可以诱导模型忽略它。TrustAgent 的做法是：
在工具真正执行的那一行代码之前，由 `ToolPolicyEnforcer` 读取控制面下发的策略对象（不是模型输出）做硬校验。
模型输出只能表达“我想调用某工具”，能不能调用由策略决定。这样即使模型被完全攻陷，被允许的能力上限依然由策略固定。

## 3. 核心数据流

### 3.1 Agent 对话（Playground）

```mermaid
sequenceDiagram
    participant U as 浏览器
    participant J as Java 控制面
    participant R as Redis
    participant P as Python 执行面
    participant D as MySQL

    U->>J: POST /api/apps/{id}/chat (JWT, body: prompt)
    J->>J: JWT 校验 + RBAC(agent:chat)
    J->>R: 滑动窗口限流 (key: user+app)
    J->>J: 参数校验 + 生成本次 traceId (UUID)
    J->>D: 写入 agent_trace(status=RUNNING)
    J->>P: POST /v1/agent/run (Header: X-Trace-Id, X-Internal-Token)
    P->>P: 输入安检（注入/PII/长度）→ 记录安全事件
    alt 输入被判定为高危
        P-->>J: blocked=true + categories
    else 通过安检
        P->>P: MockLLM 决策（工具 or 直接回答）
        P->>P: 工具调用前策略校验（白名单/次数/Schema/域名/敏感参数）
        alt 策略拒绝
            P->>P: 记录 security_event(TOOL_DENIED) 并中断该次工具调用
        else 策略允许
            P->>P: 执行模拟工具（不触碰真实资源）
        end
        P->>P: 输出安检（PII/系统提示/URL/HTML）
    end
    P-->>J: AgentRunResponse(status, output, toolCalls[], guard{}, latencyMs)
    J->>D: 落库 tool_call_record / security_event / 更新 agent_trace
    J->>D: 写审计记录（哈希链）
    J-->>U: 统一响应 { traceId, output, guard, toolCalls, elapsedMs }
```

### 3.2 批量评测（阶段2）

```mermaid
sequenceDiagram
    participant U as 浏览器
    participant J as Java 控制面
    participant R as Redis
    participant W as Python Worker
    participant D as MySQL

    U->>J: POST /api/eval/runs (Idempotency-Key)
    J->>D: 幂等校验 → INSERT eval_run(status=PENDING)
    J->>R: XADD eval.jobs {runId, datasetId, appId, mode, traceId}
    J-->>U: 202 {runId, status=PENDING}

    W->>R: XREADGROUP cg:eval (worker-1)
    W->>J: GET /internal/eval/datasets/{id}/cases (Internal Token)
    loop 每条 case（guard_on / guard_off 两轮）
        W->>W: 执行 Agent 链路，收集 actual_action/blocked/latency
        W->>J: POST /internal/eval/callback (批次, Internal Token)
        J->>D: 幂等写入 eval_case_result + 更新进度
    end
    W->>J: POST /internal/eval/callback (type=COMPLETED, metrics)
    J->>D: 复算指标 → eval_run.status=SUCCEEDED
    J->>R: XACK eval.jobs
    U->>J: GET /api/eval/runs/{id} → 进度与指标
```

### 3.3 幂等与重试的一致性边界

- **Java 侧**：`idempotency_record` 表 `UNIQUE(idem_key, scope)`，先插入后执行；
  唯一键冲突即视为重复请求，直接返回已存在资源 ID。
- **结果写入侧**：`eval_case_result` 表 `UNIQUE(run_id, case_id, guard_enabled)`，
  回调重复到达时执行“存在则跳过”，因此重复消费不会叠加统计。
- **Redis 侧**：`XACK` 只在结果成功写入后发送；Worker 崩溃时消息保留在 PEL，
  由 `XAUTOCLAIM` 重新分配；超过 `maxDeliveries` 进入死信 Stream `eval.jobs.dlq`。

## 4. 跨语言可观测性

- 入口由 Java 生成 `traceId`，写入 MDC 与响应头 `X-Trace-Id`。
- 调 Python 时通过 `X-Trace-Id` 传递，Python 用 `ContextVar` 保存并写入每条结构化日志。
- 所有落库记录（Trace / 工具调用 / 安全事件 / 审计 / 评测）都带 `trace_id`，前端可一键串联。

日志格式统一为单行 JSON 风格键值对，例如：

```
2026-09-15T10:20:30.123Z INFO  traceId=8f1c... appId=3 event=tool.denied tool=send_notification rule=TOOL_NOT_ALLOWED
```

## 5. 部署形态

```mermaid
flowchart TB
    subgraph Compose["docker-compose.yml"]
        M["mysql:8.0"]
        R["redis:7-alpine"]
        B["backend (Java)"]
        A["ai-service (Python)"]
        W["web (nginx 静态资源 + /api 反代)"]
    end
    W --> B
    B --> M
    B --> R
    B --> A
    A --> R
```

- 仅 `mysql`、`redis` 暴露端口到宿主机（演示用）；`backend` 与 `ai-service` 只在 compose 网络内互通。
- 内部 Token 通过环境变量注入，不写入镜像。

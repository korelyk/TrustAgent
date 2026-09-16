# TrustAgent 阶段0设计说明

> 本文是阶段0的落地设计基线。所有后续代码实现以此为准；如果实现中发现必须偏离，会在 `docs/API.md` /
> `docs/DATABASE.md` 的“变更记录”小节写明原因，而不是静默改掉。

## 1. 项目定位

TrustAgent 是一个 **AI Agent 安全网关 + 自动化评测 + 可验证审计** 的教学/求职级项目，包含三个可独立部署的进程：

| 进程 | 技术栈 | 职责 |
| --- | --- | --- |
| `backend`（Java 控制面） | Java 21、Spring Boot 3.5.16、Spring Security、MyBatis-Plus、Flyway、MySQL 8、Redis 7 | 身份认证与 RBAC、Agent 应用与安全策略管理、限流与幂等、Trace / 安全事件 / 审计落库、评测任务编排与指标计算 |
| `ai-service`（Python 执行面） | Python 3.12、FastAPI、Pydantic、pytest | LLM 调用（默认 MockLLMProvider）、Agent 编排、输入安全检查、工具调用前策略校验、输出安全检查、评测 Worker |
| `web`（Vue 管理端） | Vue 3、TypeScript、Vite、Element Plus | 登录、应用与策略配置、Playground、评测与报告、Trace 详情、安全事件、审计链验证 |

**设计主线（面试时的一句话）**：控制面（Java）掌握“谁能做什么、策略是什么、结果如何留痕”，执行面（Python）专注
“把 LLM/工具跑起来并按策略执行”，两侧通过 Trace ID 与内部 Token 串成一条可审计的链路；安全判断放在
**执行前**（工具真正被调用之前），并且控制面会独立复算评测指标，避免执行面自证清白。

## 2. 功能范围

### 2.1 第一版（阶段1 + 阶段2 + 阶段3）必须交付

- 三角色 RBAC：`ADMIN` / `DEVELOPER` / `AUDITOR`，同时覆盖正常授权与越权拒绝。
- Agent 应用管理与安全策略：白名单工具、最大工具调用次数、参数 JSON Schema、域名白名单、敏感参数禁止规则、
  副作用工具确认开关、任务超时、最大输入长度。
- Agent Playground 全链路：Java 认证/限流/校验 → 生成 Trace ID → Python 输入安检 → Agent 决策 →
  工具调用前策略校验 → 工具执行 → 输出安检 → Java 落库 Trace/安全事件/审计。
- 四个纯模拟工具：`calculator`、`search_local_docs`、`query_traffic_metrics`、`send_notification`（写本地 Mock Outbox）。
- 安全能力：提示注入检测（直接/间接/忽略规则/编码混淆/系统提示泄露诱导）、敏感信息检测与脱敏
  （手机号、邮箱、身份证、银行卡、API Key）、输出安全（PII / 系统提示词 / 未验证 URL / HTML 脚本）、
  Redis 限流、`Idempotency-Key` 幂等。
- 评测系统：JSONL 导入校验、`>=100` 条合成评测集、Redis Streams 异步执行、失败重试与死信、
  重复消息幂等、防护开/关对比、JSON/CSV 导出、9 类指标。
- 审计哈希链：`prev_hash` + `record_hash`，提供验证接口，能定位首个异常位置。
- 工程化：Docker Compose、GitHub Actions、OpenAPI、结构化日志、Trace ID 跨语言、k6 脚本（只生成不自动跑）。

### 2.2 第一版明确不做（写进 `docs/SECURITY_LIMITATIONS.md`）

- 不做 K8s、Elasticsearch、微服务注册中心、分布式事务。
- 不做 Kafka / MinIO / OpenTelemetry / Prometheus / Grafana（阶段4）。
- 不训练安全分类模型；注入检测是**可解释规则**，接口已按可替换分类模型设计。
- 不接入真实数据库之外的真实副作用（不真发邮件、不真查公网、不碰真实文件系统）。
- 不承诺任何安全效果数字；所有指标只能由 `docs/BENCHMARK.md` 记录的真实运行结果产生。

## 3. 目录结构

```
TrustAgent/
├─ backend/                 # Java 控制面（Maven，含 Maven Wrapper）
├─ ai-service/              # Python 执行面（FastAPI）
├─ web/                     # Vue 3 管理端
├─ datasets/                # 合成评测集（JSONL）+ 校验脚本
├─ deploy/                  # Dockerfile / nginx / compose 覆盖文件
├─ scripts/                 # 启动、初始化、验证、演示脚本
├─ k6/                      # 压测脚本（只生成，不自动执行）
├─ docs/                    # 架构、威胁模型、库表、API、指标、限制、面试题、学习笔记
├─ .env.example
├─ docker-compose.yml
├─ Makefile
└─ README.md
```

## 4. 运行时配置策略（重要）

为了让“新环境能启动”和“测试不需要外部依赖”同时成立，运行配置分成两组 profile：

| profile | 数据库 | 缓存/队列 | 用途 |
| --- | --- | --- | --- |
| 默认（无 profile）+ 环境变量 | MySQL 8 | Redis 7 | `docker-compose.yml` 的标准启动方式 |
| `dev` | H2（文件模式，Flyway 同样执行） | 内存实现（限流、评测队列） | 零外部依赖的本机联调与端到端验证 |
| `test` | H2（内存） | 内存实现 | 单元测试 / 集成测试 |

对应的抽象接口：`RateLimiter`、`IdempotencyStore`、`EvalJobPublisher`、`EvalCaseResultSink`。
**注意**：`dev` profile 下评测任务不会真正跨进程消费（内存队列），跨语言消费必须用 Redis，
这一点会在 README 与限制文档中写明，避免把 dev 环境当成生产。

## 5. 分阶段任务清单

### 阶段0（本文 + 4 份设计文档）
- 功能范围、架构图、ER 图、API 清单、威胁模型、目录结构、阶段清单。

### 阶段1：可运行 MVP
1. Maven 骨架 + `application.yml` + Flyway `V1__baseline.sql`。
2. 登录/JWT/RBAC（`sys_user`/`sys_role`/`sys_permission` 三张表 + 关联表）。
3. Agent 应用、工具、安全策略的读写 API 与前端页面。
4. Python：`LLMProvider` 接口 + `MockLLMProvider` + `OpenAICompatibleProvider`（默认不启用）。
5. Python：四个模拟工具 + 工具注册表 + 参数 Schema 校验。
6. Python：注入检测器、PII 检测/脱敏、输出防护、统一 `Detector` 接口。
7. Java → Python 的 Playground 链路 + Trace/工具调用/安全事件落库。
8. 基础 Vue 页面（登录、应用列表、策略编辑、Playground）。
9. `docker-compose.yml`、`.env.example`、`README.md` 快速启动。
10. 单元测试，验收标准见 `docs/ROADMAP.md`。

### 阶段2：评测与审计
1. JSONL 评测集（>=100 条合成数据，8 个类别）+ 导入校验 + 落库。
2. Redis Streams 异步评测：生产、消费组、重试、死信、幂等写入。
3. 指标计算（Java 权威复算 + Python 计算交叉校验）。
4. 防护开/关对比、报告 JSON/CSV 导出。
5. 安全事件查询、Trace 详情、审计哈希链与验证接口。
6. 对应测试。

### 阶段3：简历展示完善
1. 前端评测仪表盘、报告页、Trace 页、安全事件页、审计验证页。
2. Docker 一键启动脚本、CI 配置、k6 脚本（两套）。
3. 实际轻量测试运行记录。
4. 全量文档：`ARCHITECTURE` / `THREAT_MODEL` / `DATABASE` / `API` / `BENCHMARK` /
   `SECURITY_LIMITATIONS` / `INTERVIEW` / `ROADMAP` / `LEARNING_NOTES`。
5. 简历描述草稿（`docs/RESUME.md`）。

### 阶段4：可选增强（未实现，禁止提前做）
Kafka、MinIO、OpenTelemetry/Prometheus/Grafana、AgentDojo/PyRIT/Presidio 适配、真实交通预测模型、测试服务器部署。

## 6. 环境基线（本机实测）

| 组件 | 实测版本 | 说明 |
| --- | --- | --- |
| OS | Windows 11 (win32) | 脚本需同时提供 PowerShell 与 bash 版本 |
| Java | OpenJDK 21.0.10 (Microsoft) | 编译目标 21 |
| Maven | Apache Maven 3.9.12 | 同时提供 Maven Wrapper |
| Python | 3.12.10（另有 3.13） | 使用 3.12 创建虚拟环境 |
| Node / npm | v25.6.0 / 11.8.0 | 前端构建 |
| Docker | 29.2.0 | 用于 MySQL/Redis 与端到端演示 |

## 7. 术语（学习文档会展开）

- **控制面 / 执行面（control plane / data plane）**：控制面管策略与记录，执行面管真正干活。
- **RBAC（Role-Based Access Control）**：基于角色的访问控制。
- **Tamper-evident（篡改可检测）**：只能“发现被改过”，不等于“改不了”。
- **Idempotency（幂等）**：同一个请求重复提交，结果与提交一次相同。
- **Consumer Group（消费者组）**：Redis Streams 里多消费者分工且可重放消费的机制。

# TrustAgent

**AI Agent 安全网关、自动化评测与审计平台**

Java 控制面负责认证 / 授权 / 策略 / 审计 / 评测编排，Python 执行面负责 Agent 编排与内容安检，
Vue 管理端展示真实链路；三者用**内部 Token + 全链路 Trace ID** 串起来。

> 这是一个**求职作品项目**，不是商业系统。
> 所有指标与结论都来自仓库里可以重跑的测试与脚本，没有虚构的生产数据。
> 哪些做完了、哪些没做完、边界在哪，下面与 `docs/SECURITY_LIMITATIONS.md` 里都写清楚了。

---

## 一、实测结果一览

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| Java 控制面测试 | `cd backend && ./mvnw test` | **82 项通过**（含场景 D 落库回归 6 项、场景 F 真实数据 6 项） |
| Python 执行面测试 | `cd ai-service && ./.venv/bin/python -m pytest` | **267 项通过**（含故障注入点 9 项） |
| 前端构建 + 类型检查 | `cd web && npm run build` | **通过**（9 个页面） |
| 端到端演示（场景 A/B/C/D/F） | `pwsh scripts/demo-smoke.ps1` | **6 个场景全部通过** |
| 100 条批量评测实测 | `pwsh scripts/eval-e2e.ps1` | **200/200 完成**，ASR 5.0% vs 基线 100% |
| 跨语言指标一致性 | 同上（`audit: metricsMismatch`） | **none（完全一致）** |
| 检测规则修复后的重测 | `python scripts/analyze_detection_delta.py` | 精确率 0.934→**0.950**，误报率 0.200→**0.150**，真阳性未丢（57→57） |
| 干净/投毒语料对照 | `pytest tests/test_doc_corpus_ab.py` | **5 项通过** |
| 部署编排渲染校验 | `docker compose -f docker-compose.lowmem.yml config` | **通过**（内存上限合计 1536 MiB，仅 web 绑回环） |
| 服务器脚本语法 | `bash -n scripts/server/*.sh` | **11 个脚本全部通过** |
| **部署到真实 1 核 2 GB 服务器** | `./scripts/server/status.sh` | **6 个容器运行中**；上限经 **cgroup 核实生效**（合计 1536 MiB），**实测占用约 518 MiB**，无 OOMKilled |
| **真实 MySQL 迁移** | 服务器 `flyway_schema_history` | **V1 baseline / V2 seed 均 success** |
| **真实 Redis Streams 评测链路** | 服务器 `eval_run` / `eval_case_result` | **200 条与 120 条两次评测全部落库**（结果行数与 `total_cases` 逐一相等） |
| **场景 A / B 服务器复验** | `./scripts/server/smoke.sh` | A 允许执行并返回 1800；B **4 次**在工具真正执行前被拦，理由「不在该应用的白名单内」 |

| **场景 D 端到端（脱敏 + 安全事件）** | `./scripts/server/verify-scene-d.sh` | **已修复并打通**：`inputMasked` 正确脱敏、`security_event` 落库 2 条（`PII_MASKED`/`PHONE_CN`+`EMAIL`/`MASK`）、响应/审计/证据均无原文 |
| **场景 F 审计链（真实记录）** | `mvn test -Dtest=AuditChainRealDataTest` | **6/6 通过**：从服务器导出的 8 条真实记录校验通过；三种篡改均被定位；**两个能力边界也用测试固定** |
| **Worker 崩溃恢复（三项）** | `./scripts/server/verify-worker-recovery.sh` | **8 项断言通过**：结果写入前崩溃→重投完成；结果已提交未 ACK→**结果行数与完成计数均未重复**；重试超限→死信 + `FAILED` 终态 |

**未执行**：确切的**分阶段**资源序列（各阶段只做了单点采样，见 `docs/DEPLOYMENT.md` §2）、
公网 HTTPS、k6 压测、场景 F 的**线上注入**（只在隔离数据集上验证，未改线上记录）。
另：本机 Docker 引擎不可用（`HCS_E_SERVICE_NOT_AVAILABLE`），所以方式二的
`docker compose up` **未在本机拉起过容器**——服务器上的镜像是用
`FROM <旧镜像> + COPY 变更文件` 在服务器本地增量构建的，服务器上没有 JDK/Maven。
逐条依据见 `docs/DEPLOYMENT.md`。

批量评测的真实指标（控制面独立复算值）：

| 指标 | 防护开启 | 防护关闭（基线） |
| --- | ---: | ---: |
| 攻击成功率 ASR | **0.05** | **1.0** |
| 检测精确率 / 召回率 / F1 | 0.934 / 0.950 / 0.942 | 0 / 0 / 0 |
| 误报率 FPR | 0.20 | 0.0 |
| 敏感信息泄露率 | **0.40** | 1.0 |
| 整体通过率 | 0.89 | 0.30 |

公式、口径、逐条归因见 **`docs/BENCHMARK.md`**。

---

## 二、它解决什么问题

给大模型接上工具之后，风险从"说错话"变成了"**替你做了事**"。本项目把这件事拆成三层可验证的机制：

1. **内容安检**：直接与间接提示注入、PII、系统提示词泄露、可执行 HTML、未验证外链。
2. **工具调用的强制拦截点**：不允许的工具、参数不合 Schema、超次数、超时、敏感参数、副作用确认——
   全部在**工具真正执行之前**由策略层判定，而不是在提示词里提醒模型别乱来。
3. **可追溯与可评测**：每次请求一条 Trace（含每个工具调用的耗时与脱敏参数），
   每条安全事件落库，每次策略变更写进**篡改可检测（tamper-evident）的审计哈希链**；
   评测支持"关防护 / 开防护"对照，指标由**控制面独立复算**，不采信执行面上报的数字。

### 刻意的设计取舍（面试常被追问）

| 取舍 | 理由 |
| --- | --- |
| 内部接口用**独立 Token**，不复用用户 JWT | 两条凭证链路完全隔离；用户身份在 `/internal/**` 上没有意义 |
| `/internal/**` 鉴权写在**路径**上而非每个方法 | 路径级"默认拒绝"：新增方法漏写注解不会漏权 |
| 缺 `Idempotency-Key` 直接 **400**，不静默降级 | 静默降级会给调用方一个语义错误的成功，一次超时重试就多跑一轮评测 |
| 创建任务返回 **202**，幂等重放返回 **200** | 让调用方区分"新受理"与"已提交过" |
| 控制面**复算**指标并覆盖上报值 | 执行面可能被攻破或算错；不采信下游上报的数字 |
| 评测台的 PII 识别**不用产品自己的检测器** | 否则是循环论证：检测器认不出的泄露永远测不出来（实测曾把 40% 泄露测成 0） |
| MockLLM **故意不自我审查** | 把安全寄托在模型自觉上是错的；也让"防护关闭"的对照组能真的复现攻击成功 |
| 前端**禁止 `v-html`** + CSP | 输出侧虽然也转义危险 HTML，但安全设计不能只依赖一道防线（CI 里有强制检查） |
| H2 用 `provided` + 只在 `repackage` 里 exclude | dev/test 需要，生产 fat jar 绝不能带一个用不到的数据库驱动 |

---

## 三、技术栈

| 层 | 选型 |
| --- | --- |
| Java 控制面 | Java 21、Spring Boot 3.5.16、Spring Security + JWT、MyBatis-Plus 3.5.17、Flyway、MySQL 8、Redis 7、springdoc-openapi、JUnit 5 + Mockito |
| Python 执行面 | Python 3.12、FastAPI 0.141、Pydantic 2、pytest、redis-py、jsonschema |
| 前端 | Vue 3.5 + TypeScript + Vite 6 + Element Plus + Pinia + Vue Router + axios |
| 工程化 | Maven Wrapper、Docker Compose、跨平台启动脚本、Makefile、GitHub Actions、k6 |

---

## 四、目录结构

```
TrustAgent/
├── backend/              Java 控制面（Maven Wrapper 已内置）
├── ai-service/           Python 执行面（含 .venv、Agent 编排、安检、评测 Worker）
├── web/                  Vue 3 管理端（9 个页面）
├── datasets/             100 条合成评测集 + 生成脚本
├── deploy/               Dockerfile 与 MySQL 配置
├── scripts/              初始化 / 启动 / 端到端验证脚本
├── k6/                   两套压测脚本（只提供，不自动执行）
├── docs/                 架构、威胁模型、数据库、API、基准、边界、学习笔记、面试、简历
├── docker-compose.yml
├── Makefile
└── .env.example
```

---

## 五、快速启动

### 方式一：零依赖本地联调（**已实测通过**）

不需要 MySQL、不需要 Redis、不需要任何 API Key。H2 文件库 + 内存限流/队列 + 确定性 MockLLM。

```powershell
# 1) 生成 .env（随机密钥，不会提交；会打印管理员口令，请记下）
pwsh scripts/init-dev-env.ps1

# 2) 终端 1：Python 执行面（首次会自动建 venv 并装依赖）
pwsh scripts/start-ai-service.ps1

# 3) 终端 2：Java 控制面（dev profile）
pwsh scripts/start-backend-dev.ps1

# 4) 终端 3：Vue 管理端
cd web; npm install; npm run dev     # http://localhost:5173

# 5) 另开终端：端到端验证
pwsh scripts/demo-smoke.ps1          # 演示场景 A/B/C/D/F
pwsh scripts/eval-e2e.ps1            # 100 条批量评测 + 指标 + 报告导出
```

控制面接口文档：<http://localhost:8080/swagger-ui.html>

### 方式二：docker compose（**仅验证了 compose 语法，未在本机拉起容器**）

首次会拉取 mysql / redis / python / maven+JDK 镜像（数 GB），请自行确认后再执行。

```bash
pwsh scripts/init-dev-env.ps1
docker compose up -d --build
docker compose logs -f backend
```

口令全部通过 `${VAR:?...}` 引用：**未设置就启动失败**，不会回退到弱默认值。
MySQL / Redis 端口只绑定 `127.0.0.1`。这份 compose 也包含 **web（Caddy）**：
它提供前端构建产物并把 `/api` 反代到控制面。

### 方式三：服务器演示部署（1 核 2 GB，**已在真实服务器上部署并部分复验**）

面向"投简历时能现场演示"的场景：资源受控、可启动、可重启、可备份、可回滚。
默认**私有模式**——入口只绑宿主机回环地址，通过 SSH 隧道访问，不暴露公网。

```bash
# 本地：构建产物（前端 + jar + 镜像 tar），串行执行并限制内存
pwsh scripts/build-release.ps1 -Tag v0.1.0-demo1

# 上传后，在服务器上：
./scripts/server/init-server-env.sh     # 生成随机强密钥的 .env（不覆盖已有）
./scripts/server/preflight.sh           # 部署前检查（只读）
./scripts/server/deploy.sh              # 发布（迁移前自动备份数据库）
./scripts/server/smoke.sh               # 端到端冒烟（场景 A/B/C/D/F，经反向代理）
./scripts/server/status.sh              # 内存 / 重启 / 端口暴露 / 队列积压
```

访问方式（在本机执行）：**入口只绑服务器的回环地址，必须先建 SSH 隧道**，
这是私有模式的安全属性，不是配置错误——从公网直接访问不到是**预期行为**。

```bash
# 本机：建立隧道。这个窗口要保持打开，Ctrl+C 断开。
# 服务器地址从 .env 的 TRUSTAGENT_SSH_HOST 读（也可以 -Server <ip> 显式传入）：
pwsh scripts/tunnel.ps1

# 等价的原始命令（-F none 用来绕过本机损坏的 ~/.ssh/config）
ssh -F none -i ~/.ssh/trustagent_deploy -N -L 8090:127.0.0.1:8090 <user>@<server>

# 然后浏览器打开 http://localhost:8090
```

排障：`pwsh scripts/tunnel.ps1 -Check` —— 报告隧道通不通。
"端口无人监听"是隧道断了；"隧道通畅但页面打不开"才是服务器侧问题。

常用运维命令：

| 命令 | 作用 |
| --- | --- |
| `deploy.sh` / `status.sh` / `stop.sh` | 启动 / 查看状态 / 停止（**始终保留数据卷**） |
| `backup.sh` / `restore.sh` | 备份（MySQL + Redis + .env）/ 恢复（默认恢复到**临时库**） |
| `rollback.sh` | 回滚镜像版本（**数据库不会一起回滚**） |
| `verify-stack.sh` | 真实 MySQL + Redis Streams 验证 |

完整的资源预算、调优依据、安全清单与故障排查见 **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**。

> ⚠️ **诚实说明**：这套部署**已在真实的 1 核 2 GB 服务器上跑起来**——
> 6 个容器运行中、内存上限经 cgroup 核实生效、真实 MySQL 迁移与
> Redis Streams 评测链路均已复验（见第一节表格）。
>
> 但仍有**未验证项**：场景 D 脱敏与安全事件、场景 F 篡改注入、公网 HTTPS、
> k6 压测、分阶段资源记录。**所以不要把它当成"全部验收通过"。**
> 逐条依据见 `docs/DEPLOYMENT.md`。

---

## 六、演示场景

| 场景 | 内容 | 覆盖方式 |
| --- | --- | --- |
| A | 正常调用：`calculator` 被允许并真实执行 | `demo-smoke.ps1` |
| B | 越权调用：诱导 Agent 调用未授权的 `send_notification`，执行前被拦截 | `demo-smoke.ps1` |
| C | 间接注入：演示文档内藏的恶意指令被检出并阻断 | `demo-smoke.ps1` |
| D | 敏感信息：手机号/邮箱/API Key 被脱敏并生成安全事件 | `demo-smoke.ps1` |
| E | 批量评测 100 条，对比防护开关 | `eval-e2e.ps1` |
| F | 审计链验证（完整链 + 篡改定位） | 完整链在脚本里；篡改步骤见下（手工） |

### 场景 F 手工演示（篡改检测）

先 `GET /api/audit/verify` 应得到 `valid=true`。然后改一条审计记录：

```sql
-- dev profile：打开 http://localhost:8080/h2-console（JDBC URL 见 application-dev.yml）
-- compose  ：docker compose exec mysql mysql -utrustagent -p trustagent
update audit_log set detail_json = '{"tampered":true}' where seq = 1;
```

再次验证应得到 `valid=false`、`firstBrokenSeq=1`、`reason=RECORD_HASH_MISMATCH`。
演示完请改回原值（自动化测试与脚本都会在 `finally` 里自行恢复）。

> **诚实说明**：这是 **tamper-evident（篡改可检测）**，不是"不可篡改"。
> 拿到数据库全权限的攻击者可以重算整条链。生产还需要外部可信存证、签名或 WORM 存储。
> **这不是区块链。**

---

## 七、界面

截图在 `docs/screenshots/`（由真实浏览器访问真实后端生成，数据来自本地演示库）：

| 页面 | 截图 |
| --- | --- |
| 登录 | `docs/screenshots/login.png` |
| Agent Playground（工具调用 + 安全事件） | `docs/screenshots/playground.png` |
| 评测报告（防护开/关指标对照） | `docs/screenshots/eval-report.png` |

管理端共 9 个页面：登录、应用列表、策略编辑、Playground、评测集与任务、评测报告、
Trace 详情、安全事件、审计链验证。

**界面上的权限只控制"按钮显不显示"，不是安全边界**——真正的拦截在后端 `@PreAuthorize`。

---

## 八、测试

```bash
cd backend    && ./mvnw test                       # 82 项
cd ai-service && ./.venv/bin/python -m pytest       # 267 项
cd web        && npm run build                      # 类型检查 + 构建
# 或
make test
```

Java 侧覆盖：登录与 JWT 校验、RBAC 授权、越权拒绝、策略保存与乐观锁、登录限流、
幂等创建评测任务、内部回调鉴权、审计哈希链生成/验证/篡改定位、指标公式复算。

Python 侧覆盖：正常 Prompt、直接/间接提示注入、PII 识别与脱敏、未授权工具阻断、
参数 Schema 校验（含"Mock 生成的参数必须通过工具自己的 Schema"）、MockLLM 确定性、
编码混淆、指标计算、评测 Worker 的轮次/分批/幂等/重试/死信决策。

### 压测（**未执行**）

`k6/control-plane.js` 与 `k6/full-chain.js` 只提供脚本。高并发压测属于需要明确授权的重操作，
本项目**没有跑过任何压测**，因此不提供任何性能声明。使用前请先读 `k6/README.md`
（必须先调高限流额度，否则测到的是限流器）。

---

## 九、本机的磁盘与环境约定

这个仓库**刻意不让构建缓存落到 C 盘**：

| 内容 | 位置 |
| --- | --- |
| Maven 本地仓库 | `E:\dev-cache\maven-repository` |
| Maven Wrapper 下载的 Maven 发行版 | `E:\dev-cache\maven-home\wrapper` |
| pip / npm 缓存 | `E:\dev-cache\pip-cache` / `E:\dev-cache\npm-cache` |
| Python 虚拟环境 | `ai-service/.venv`（项目内） |
| node_modules | `web/node_modules`（项目内） |

想连 Maven 的用户级配置也放到 E 盘：`setx MAVEN_USER_HOME "E:\dev-cache\maven-home"`。
C 盘只保留一个不到 1 KB 的 `%USERPROFILE%\.m2\settings.xml` 用于把仓库指向 E 盘。

---

## 十、已知未实现 / 未验证（**不包装成"已完成"**）

| 项 | 状态 |
| --- | --- |
| Redis Streams 评测队列 | **已在服务器真实 Redis 上复验**：200 条与 120 条两次评测全部落库，结果行数与 `total_cases` 逐一相等。**未验证**：故障注入用例（处理中退出、结果写入后 ACK 前退出、重启认领、重复投递、死信投递）——`verify-stack.sh` 的这些场景未在服务器上执行 |
| 真实 MySQL 8 / Redis 7 | **已在服务器上复验**：Flyway V1/V2 迁移成功、唯一约束与幂等写入生效、重启后数据仍在。**未验证**：崩溃中断时的事务边界 |
| 场景 D：脱敏 + 安全事件 | **已修复并端到端验证**（原因：控制面只写 debug 日志、从不落库；而执行面没有数据库驱动，无法自行上报）。**未覆盖**：输出侧事件只经 `/v1/guard/inspect` 验证了**检测器**，未走完整的"聊天→落库"链路 |
| 场景 D 边界：**工具输出侧的 PII 不脱敏** | 按设计如此（`GuardPipeline.inspect_tool_output` 只做间接注入检测，脱敏器是恒等函数）。**风险**：工具输出里的 PII 会进入模型上下文，只靠最终输出的 `inspect_output` 兜底；真实模型只要改写号码格式即可绕过。**未修复，仅记录** |
| 场景 F：审计链篡改检测 | **已用服务器真实记录验证**（8 条）：三种篡改均被定位。**两个边界也用测试固定**：删除链尾**检测不到**；攻击者若能重算整链**检测不到**（无外部存证锚点） |
| 分阶段资源记录 | **部分完成**：服务器 `.local/server/resource-log.csv` 已有采样（`status.sh --record`），但仍是单点，缺少"初始化 / 请求高峰 / 批量评测 / 重启"各阶段序列。**批量评测期间的内存从未被测过** |
| 宿主 swap | 宿主机已有 2047 MiB swap，实测占用 36 MiB（**非本项目创建**）。容器内 `memswap_limit == memory`，即容器**无法使用** swap；但宿主层仍开着 swap，若整体内存吃紧仍可能被换出 |
| k6 压测 | **未执行**（需明确授权） |
| 多实例部署 | **未验证**：限流的内存实现只保证单实例正确 |
| 同形异义字符（homoglyph）映射 | 未实现（阶段4） |
| Presidio / PyRIT / AgentDojo 适配 | 未实现（阶段4） |
| Prometheus / Grafana / OpenTelemetry | 未实现（阶段4） |
| 幂等记录清理策略 | 未实现（表会持续增长） |
| 密钥管理（KMS/Vault）与内部凭证轮换 | 未实现 |
| JWT 刷新令牌 / 吊销 / MFA | 未实现 |
| 生产环境适用性 | **明确不适用** |

---

## 十一、文档索引

| 文档 | 内容 |
| --- | --- |
| `docs/ARCHITECTURE.md` | 架构图、组件职责、数据流 |
| `docs/THREAT_MODEL.md` | 资产、信任边界、攻击面、风险与缓解 |
| `docs/DATABASE.md` | ER 图、表结构、索引、字段说明 |
| `docs/API.md` | 核心接口与内部接口、变更记录 |
| `docs/BENCHMARK.md` | 指标公式、测试环境、**实测结果与逐条归因** |
| `docs/SECURITY_LIMITATIONS.md` | 这个项目**不能**解决什么（含实测出的能力边界） |
| `docs/LEARNING_NOTES.md` | 用 Java 初学者的语言讲清每个模块 + 术语中英对照 |
| `docs/INTERVIEW.md` | 围绕本项目的 36 个面试问答 |
| `docs/RESUME.md` | 简历描述草稿（含"不适合写的说法"） |
| `docs/ROADMAP.md` / `docs/PHASE0_DESIGN.md` | 阶段划分与设计范围 |
| `datasets/README.md` | 评测集格式、来源声明、写用例时踩过的坑 |

---

## 十二、安全声明

- 四个内置工具**不触碰任何真实资源**：不访问真实文件系统、不发真实邮件/短信、不请求公网敏感接口，
  `send_notification` 只写本地 Mock Outbox。
- 默认使用确定性 MockLLM，不需要任何 API Key；接真实模型时密钥只走环境变量。
- 前端只用文本方式渲染模型输出（禁止 `v-html` / `innerHTML`），并配置了 CSP；
  CI 里有强制检查。
- 评测集与所有演示数据都是**合成数据**，其中手机号/邮箱/身份证/银行卡/API Key 均为编造样本。
- 本项目是教学/作品性质，**不声称可用于生产**；适用范围与缺口见 `docs/SECURITY_LIMITATIONS.md`。

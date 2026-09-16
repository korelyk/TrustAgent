# TrustAgent 演示部署指南（1 核 2 GB）

面向目标：在一台 **1 核 2 GB** 的 Linux 服务器上，跑起一个**资源受控、可启动、可重启、
可备份、可回滚**的演示环境。默认只通过 SSH 隧道访问，不暴露公网。

> 本文里的每个数字都区分两类：**实测**（有命令输出可复现）与**推算**（按官方文档与
> 代码约束推导，尚未在目标机器上量过）。这个区分不是谨慎，是必要的——
> 如果把推算值当实测值用，第一次上生产就会在"为什么内存不够"上卡很久。

---

## 0. 当前验证状态（先看这个）

**部署已在真实的 1 核 2 GB 服务器上完成**，并做了部分复验。下表如实区分
「已验证」与「未验证」——**没跑过的一律不写成跑过**。

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| 低配编排语法与变量渲染 | **已验证** | `docker compose -f docker-compose.lowmem.yml config` 退出码 0 |
| 内存上限写进编排 | **已验证** | 渲染结果：backend/mysql 512 MiB、ai-service 192、ai-worker 160、redis 96、web 64，合计 1536 MiB |
| **内存上限真正生效** | **已验证（cgroup 核实）** | 容器内 `memory.max`：backend 512 MiB、mysql 512 MiB —— 不是只写在 compose 里 |
| 容器内禁用 swap | **已验证** | `memswap_limit == memory`，6 个容器均是 |
| 端口暴露面 | **已验证** | 仅 web 发布 `127.0.0.1:8090->8080`，其余容器 `Ports` 全为 `null` |
| 日志轮转 | **已验证** | `json-file`，`max-size=10m`、`max-file=3` |
| 容器运行稳定性 | **已验证** | 6 个容器 `running`，`OOMKilled=false`，`RestartCount=0` |
| Java 控制面测试 | **已验证** | `mvn test` → `Tests run: 82, Failures: 0, Errors: 0` |
| Python 执行面测试 | **已验证** | `pytest` → `267 passed` |
| 服务器脚本 bash 语法 | **已验证** | `bash -n` 对 `scripts/server/` 下 14 个脚本全部通过（含新增/修改项） |
| 前端构建产物存在 | **已验证** | `web/dist/` 28 个文件；经反向代理 `/` 返回 200 |
| Caddyfile 被 caddy 接受 | **已验证** | web 容器 healthy、`/healthz` 200 —— 配置有语法错就起不来 |
| **真实 MySQL 上跑 Flyway** | **已验证** | `flyway_schema_history`：V1 baseline 2922ms、V2 seed 67ms，均 success |
| **异步评测链路（Redis Streams → MySQL）** | **已验证** | `eval_run` id=4/5：200 条与 120 条 SUCCEEDED，结果行数与 `total_cases` 逐一相等 |
| **场景 A（正常工具调用）** | **已验证** | `tool_call_record`：calculator allowed=1 |
| **场景 B（越权调用执行前拦截）** | **已验证** | send_notification allowed=0 ×4，`deny_reason`=「不在该应用的白名单内」 |
| **1 核 2 GB 上的真实内存占用** | **已验证（单点采样）** | 6 容器合计约 **518 MiB** / 上限 1536 MiB；宿主 `MemAvailable 940 MiB`（详见 §2） |
| 镜像构建（docker build） | **未验证（本机）** | 本机 Docker 引擎 `HCS_E_SERVICE_NOT_AVAILABLE`；服务器上用的是预构建产物 |
| 故障注入（处理中退出 / ACK 前退出 / 重启认领 / 死信） | **已验证** | `./scripts/server/verify-worker-recovery.sh`：三项恢复场景 8 项断言通过；重投后结果行数与完成计数**均未重复** |
| 场景 D（脱敏 + 安全事件） | **已验证（修复后）** | `./scripts/server/verify-scene-d.sh`：`inputMasked` 正确脱敏、`security_event` 落库 2 条、响应/审计/证据无原文 |
| 场景 D 边界（工具输出侧 PII） | **已知缺口，仍未修复** | `inspect_tool_output` 只做间接注入检测，PII 不脱敏。本轮修复**未**动这里（属于设计取舍，不是遗漏）：工具输出侧只做注入检测，脱敏发生在响应出站前 |
| 场景 F（审计链篡改注入） | **已验证（真实记录）** | `AuditChainRealDataTest` 6/6；删除链尾与整链重算**检测不到**，已用测试固定为已知边界 |
| 分阶段资源记录 | **部分完成** | `.local/server/resource-log.csv` 已有采样；仍缺"初始化 / 请求高峰 / 批量评测 / 重启"各阶段序列 |
| **公网 HTTPS 证书被浏览器信任** | **未验证** | 无域名、未启用公网模式 |
| k6 压测 | **未执行**（按约定只提供脚本） | — |

**结论：核心链路已在 1 核 2 GB 上跑通并稳定运行，但验收未全部完成。**

正确表述是「核心链路已验证（部署、迁移、异步评测、场景 A/B），
故障注入与安全场景的部分验证待补」。**不要说成"全部验收通过"。**

### 当前运行版本与源码的对应关系

**版本标识方式**：三个应用服务共用一个 tag，一次发布是一套**自洽的版本**，
而不是零散地各改各的。镜像 ID 才是权威依据，tag 只是别名。

| 服务 | 镜像 tag | 镜像 ID |
| --- | --- | --- |
| backend | `trustagent-backend:v0.1.0-demo2` | `145f0337d305` |
| ai-service / ai-worker | `trustagent-ai-service:v0.1.0-demo2` | `ac0709749ef5` |
| web | `trustagent-web:v0.1.0-demo2` | `7073e6b745f4`（复用 demo1 的层，前端未变更） |

**产物级对应**（镜像 ID 会变，产物哈希才是能追溯回源码的东西）：

| 产物 | SHA256 | 来源 |
| --- | --- | --- |
| `app.jar` | `e9e8df6297b225bca6bc6ff566bc4f06458f4ea93c196005453904a4bda18d01` | 本地 `mvn -DskipTests package`（JDK 21） |
| `app/eval/worker.py` | `c6ec85cd02cb362f06dc6213d5d33233009c36f1e7d63435626f6169c5370d76` | 本仓库 `ai-service/app/eval/worker.py` |

> 这两个值是**完整**的 SHA256（此前文档里用省略号截断，导致"按哈希追溯"这个承诺无法执行）。
> `app/eval/worker.py` 的值可用 `certutil -hashfile ai-service/app/eval/worker.py SHA256` 复核。
> `app.jar` 的值对应的是 **v0.1.0-demo2 部署时**的那份产物；本仓库的修复工作之后，
> `backend/target/` 下的 jar 已经是新的（源码已变），因此**不能**用它来核对线上那一份——
> 要核对必须用服务器上导出的 jar。这一点是刻意的：产物哈希一旦被"就地更新"，
> 它就失去了追溯能力。

**怎么确认"测的就是当前版本"**：把本地 `ai-service/app/**/*.py` 逐个与服务器源码树比对，
当时的结果是 44 个文件中 **41 个一致、唯一不同的是 `app/eval/worker.py`**，正是那次改动。
这排除了"发布了一批无关变更却不知道"的情况。

> **注意（本轮修复后）**：上面这段描述的是 **v0.1.0-demo2 发布当时**的状态。
> 本轮修复改动了 `ai-service` 的多个文件（guardrails / agent / eval / tools / api / config 等），
> 因此**现在服务器上跑的不是当前仓库的代码**——差异是已知且有意保留的。
> 要把它变成一致的状态，需要重新走一次发布流程（见 §3）。

**回退**：`v0.1.0-demo1` 的镜像仍在本地（backend `f29d324299d5`、ai-service `824fb9c945c1`），
改回 `.env` 的 `TRUSTAGENT_IMAGE_TAG=v0.1.0-demo1` 再逐个服务 `up -d --no-deps` 即可。

> **代码回滚 ≠ 数据库回滚。** demo1→demo2 没有引入任何 Flyway 迁移（迁移历史仍是 V1/V2），
> 所以这次回滚不需要动数据。将来若回滚跨越了迁移版本，必须先备份、并按 §5 的方式处理。

**构建方式的一个现实约束**：服务器上**没有 JDK/Maven**，本机 Docker 引擎也不可用
（`HCS_E_SERVICE_NOT_AVAILABLE`）。所以发布用的是
`FROM <旧镜像> + COPY 变更文件` 在服务器本地做**增量构建**——
纯文件层复制，不下载、不编译、内存开销可忽略。等本机 Docker 恢复后，
应改回 `scripts/build-release.ps1` 走完整镜像构建，并把构建好的镜像推送/导入。

### 仍待补的验证

```bash
# 服务器上补跑（不依赖本机 Docker）
./scripts/server/verify-stack.sh          # 含故障注入场景（处理中退出 / ACK 前退出 / 重启认领 / 死信）

# 批量评测期间连续采样内存（目前只测过空闲态，峰值从未被测）
./scripts/server/status.sh --record       # 在评测进行中反复执行，攒出各阶段序列
```

```bash
# 本机 Docker 恢复后（需管理员权限修复 Hyper-V/WSL 宿主计算服务）
pwsh scripts/build-release.ps1 -Tag v0.1.0-demo2   # 自建镜像并核对校验值
```

公网模式（HTTPS + 鉴权 + 接口隔离）在通过 §7 检查清单前**不要启用**。

---

## 1. 运行模式与访问方式

### 默认：私有演示模式（本文的主线）

```
浏览器 ──(SSH 隧道)──> 服务器 127.0.0.1:8090 ──> Caddy 容器 ──┬──> /api/*      → backend:8080
                                                             ├──> 其它路径    → /srv（Vue 构建产物）
                                                             └──> /internal/* → 404
```

- 宿主机上**只有 Caddy 的 8090 绑在 127.0.0.1**。
- MySQL / Redis / 控制面 / 执行面 / Worker **完全不发布端口**（用 compose 的 `expose`，
  只在容器网络上可见）。
- 认证由 SSH 承担，因此代理层**不需要**再加一套认证；而且**不能**加 HTTP Basic，
  理由见 `deploy/Caddyfile.private` 的注释（它会和应用的 `Authorization: Bearer` 争用同一个头）。

访问方式（在**你自己的电脑**上执行）：

```bash
ssh -N -L 8090:127.0.0.1:8090 <user>@<server>
# 保持这条命令不关，然后浏览器打开：
#   http://localhost:8090
```

`-N` 表示不执行远程命令，只做端口转发。用 `-L 8090:127.0.0.1:8090` 而不是
`-L 8090:<公网IP>:8090`：前者连的是服务器上的回环地址，后者要绕一圈公网。

### 可选：公网 HTTPS 模式

**默认不启用**，且启用前必须逐项通过：

- [ ] 有解析到本机公网 IP 的域名（Let's Encrypt 不为裸 IP 签发证书）
- [ ] 从**外部网络**实测浏览器地址栏是锁形图标、证书链被系统信任
      （"http 能打开"不等于证书可信：自签证书、中间人代理都会给出不被信任的证书）
- [ ] `deploy/Caddyfile.public` 里的 IP 白名单已替换成真实出口 IP
      （示例值 `203.0.113.7` / `198.51.100.0/24` 是 RFC 5737 文档专用网段，不会误放行）
- [ ] 已确认 `/internal/**`、`/swagger-ui*`、`/v3/api-docs*` 在公网被拒绝
- [ ] 已确认 MySQL/Redis/控制面/执行面在宿主机上**没有任何**监听（见 `status.sh` 第 5 节）
- [ ] 已接受：这是**演示环境**，不放任何真实数据

**没有可信证书时保持私有模式。** 不要退回"公网明文 HTTP"——那会让登录口令与 JWT
在公网上明文传输，比"别人访问不到"糟糕得多。

### 运行模式：Mock 模型 vs 真实模型

| | 默认（Mock） | 可选（真实模型） |
| --- | --- | --- |
| 变量 | `TRUSTAGENT_LLM_PROVIDER=mock` | `TRUSTAGENT_LLM_PROVIDER=openai_compatible` |
| 需要 API Key | 不需要 | 需要 |
| 费用 | 0 | 按量计费 |
| 确定性 | 完全确定（同输入同输出） | 不确定 |

**默认关闭真实模型接入。** 除费用外还有一个安全原因：允许配置任意 `OPENAI_BASE_URL`
等于给服务一个"访问任意地址"的能力，这是 SSRF 的典型入口。开启前请读
`docs/SECURITY_LIMITATIONS.md`。

页面上必须让用户能分清"真实后端链路"与"Mock 推理"：后端、数据库、队列、审计、
安全检查都是**真的**；只有模型的推理部分由确定性 Mock 代替。

---

## 2. 资源预算（1 核 2 GB）

容器内存上限合计 **1536 MiB**，留给系统与 Docker 约 **512 MiB**：

| 服务 | 上限 | 为什么是这个值 |
| --- | --- | --- |
| backend | 512 MiB | JVM 只给堆 50%（约 256 MiB），其余留给 metaspace / code cache / 直接内存 / 线程栈 |
| mysql | 512 MiB | `performance_schema=OFF` + `innodb_buffer_pool_size=128M` |
| ai-service | 192 MiB | 单 uvicorn 进程，`--limit-concurrency 20` |
| ai-worker | 160 MiB | 独立进程，批大小 10 |
| redis | 96 MiB | 数据 64 MiB（`noeviction`）+ 约 32 MiB 给 AOF 重写与客户端缓冲 |
| web (Caddy) | 64 MiB | 静态文件 + 反代 |

### 实测占用（同一台 1 核 2 GB，私有模式）

`./scripts/server/status.sh` 在**空闲/低负载**下的采样：

| 服务 | 上限 | 实测占用 | 占上限 | 余量 |
| --- | ---: | ---: | ---: | ---: |
| backend | 512 MiB | 240.7 MiB | 47% | 271 MiB |
| mysql | 512 MiB | 164.2 MiB | 32% | 348 MiB |
| ai-service | 192 MiB | 45.5 MiB | 24% | 147 MiB |
| ai-worker | 160 MiB | 38.4 MiB | 24% | 122 MiB |
| web (Caddy) | 64 MiB | 23.7 MiB | 37% | 40 MiB |
| redis | 96 MiB | 5.5 MiB | 6% | 90 MiB |
| **合计** | **1536 MiB** | **约 518 MiB** | **34%** | **约 1018 MiB** |

宿主侧同一时刻：`MemTotal 1967 MiB`、`MemAvailable 940 MiB`、
swap 2047 MiB（已用 36 MiB，**非本项目创建**）、CPU 负载(1min) `1.35` / 1 核。

**这些数字怎么用、不能怎么用：**

* 它是**单点采样**，不是峰值。**评测执行期间的内存没有被测量过** ——
  真的要约峰值，得在跑批量评测时连续采样（见 §4「资源观测」）。
* 容器合计 518 MiB **不等于**宿主总占用：Docker 守护进程、containerd、
  日志缓冲都在容器之外，评估余量时必须把它们算进去。
* 上限 34% 的占用率**不等于**"可以砍掉一半内存"。余量要留给
  JVM 堆增长、MySQL 临时表、评测批次的瞬时峰值。
* CPU 负载 1.35 / 1 核：**已经超过 1**。空闲状态下偏高，说明这台机器
  并非完全闲置。1 核上做任何耗时操作都会明显拖慢交互响应。

### JVM 参数为什么不能只给堆

`backend` 的 512 MiB 里，**不能把 512 MiB 全给 Java 堆**。除堆之外还有几块必须的内存：

- **Metaspace**（类元数据）：Spring Boot + MyBatis-Plus + Flyway 实测通常 70~90 MiB
- **Code cache**（JIT 编译产物）：配合 `TieredStopAtLevel=1` 限制在 32 MiB
- **直接内存**：NIO、文件拷贝、报告导出会用到
- **线程栈**：每个线程约 1 MiB（虚拟，按需提交）
- **GC 结构**

把上限全给堆的结果不是"跑得更快"，而是**容器在初始化阶段就被 OOMKilled**，
且报错信息完全指不到堆。所以低配值取：

```
-XX:MaxRAMPercentage=50      堆约 256 MiB（JDK 能识别 cgroup 的 mem_limit）
-XX:MaxMetaspaceSize=96m
-XX:ReservedCodeCacheSize=32m
-XX:MaxDirectMemorySize=32m
-XX:ActiveProcessorCount=1   不让 JVM 按宿主核数推算 GC/编译线程数
-XX:+UseSerialGC             1 核 + 小堆：比 G1 更省内存、线程更少
-XX:TieredStopAtLevel=1      只用 C1：启动更快、code cache 更省
```

这些值写在 `docker-compose.lowmem.yml` 的 `JAVA_OPTS` 里。
**注意两个后端 Dockerfile（`deploy/backend.Dockerfile` 与服务器实际使用的
`deploy/backend-runtime.Dockerfile`）的 ENTRYPOINT 都刻意不含硬编码的 `-XX:` 参数**：
`JAVA_TOOL_OPTIONS` 在命令行参数之前生效，如果 ENTRYPOINT 里写了
`-XX:MaxRAMPercentage=75`，上面调成 50 会被它覆盖——表现为"按文档调了内存，
实际堆还是 75%，然后被 OOMKilled"，而从 compose 文件上完全看不出问题。

### 内存不足时的行为（分服务）

设计意图是**明确失败**，不是静默降级：

| 服务 | 内存耗尽时的行为 |
| --- | --- |
| Redis | `noeviction` → 写命令返回 OOM 错误（**不会**淘汰队列键） |
| 控制面 | 容器被 OOMKilled，`status.sh` 会标出来 |
| MySQL | 容器被 OOMKilled；InnoDB 崩溃恢复会回放 redo log |
| 队列 | 消息可能停留在 PEL 中，Worker 重启后会重新认领 |

**本部署不创建 swap**，也不把"持续依赖 swap"当作健康状态。
`memswap_limit` 被刻意设为与 `mem_limit` 相等（容器不允许使用 swap）：
宿主若有 swap，容器宁可被 OOMKill 也不要靠换页苟活——持续换页只是把崩溃
推迟到更难排查的时刻。

---

## 3. 部署步骤

### 3.1 前置要求

服务器上：

- Linux，x86_64 或 aarch64（`preflight.sh` 会检查）
- Docker + compose 插件
- `curl`、`jq`（冒烟与验证脚本需要；**不在本轮自动安装的授权范围内**，需你确认后自行安装）
- 至少 20 GB 可用磁盘（40 GB 更从容）
- 至少 2 GB 内存（`preflight.sh` 会按 1536 + 450 MiB 的预算判断）

**不要把本机的 Python 虚拟环境复制到服务器**：`ai-service/.venv` 是 Windows 二进制，
在 Linux 上完全不可用。服务器上的 Python 只存在于容器内。

### 3.2 本地构建

```powershell
# 版本号不能是 latest —— 出问题时无法判断线上跑的是哪一版
pwsh scripts/build-release.ps1 -Tag v0.1.0-demo1
```

产物：

```
web/dist/                             前端静态文件
backend/target/trustagent-backend-0.1.0.jar
deploy/artifacts/trustagent-backend-0.1.0.jar   ← 上面那份的同步副本（构建时校验 SHA256 一致）
deploy/images/trustagent-*-v0.1.0-demo1.tar
deploy/images/SHA256SUMS-v0.1.0-demo1.txt
```

脚本会**串行**执行并限制内存（Maven `-Xmx1024m`、Node `--max-old-space-size=2048`）。
它**不会**推送 Git、不会发布镜像、不会连接任何服务器。

若本机资源不足或没有 Docker，用 `.github/workflows/build.yml`
（在 GitHub 页面手动 Run workflow，产物上传为 artifact，**不推送任何镜像仓库**）。

### 3.3 上传

```bash
# 在本地执行
scp -r deploy/images/*-v0.1.0-demo1.tar deploy/ docker-compose.lowmem.yml scripts/ \
    <user>@<server>:/opt/trustagent/
```

`/opt/trustagent` 是默认部署目录。`preflight.sh` 会检查它是否已存在：
若存在且**不是**本项目的部署（没有 `docker-compose.lowmem.yml`），
脚本会**报告冲突并拒绝**，不会覆盖。

### 3.4 服务器上初始化

```bash
cd /opt/trustagent
chmod +x scripts/server/*.sh

# 生成 .env（随机强密钥，权限 600，不覆盖已有文件，不回显密钥）
./scripts/server/init-server-env.sh

# 把版本号填进去（镜像必须显式带版本）
sed -i 's|^TRUSTAGENT_IMAGE_TAG=.*|TRUSTAGENT_IMAGE_TAG=v0.1.0-demo1|' .env

# 检查
./scripts/server/preflight.sh
```

### 3.5 发布

```bash
./scripts/server/deploy.sh
```

`deploy.sh` 的顺序与原因：

1. **导入预构建镜像**（服务器上不构建，避免和 MySQL/JVM 抢内存）
2. **迁移前备份数据库** —— Flyway 迁移是单向的，没有备份就无法完整回滚
3. **先停旧版再起新版** —— 2 GB 机器跑不下两套服务
4. 校验编排配置
5. `docker compose up -d`
6. 依次等待 mysql → redis → ai-service → backend → web 就绪

### 3.6 验证

```bash
./scripts/server/smoke.sh         # 演示场景 A/B/C/D/F，经反向代理
./scripts/server/status.sh        # 内存/CPU/重启/端口暴露/队列积压
./scripts/server/verify-stack.sh  # 真实 MySQL + Redis Streams（需 Docker 可用）
```

### 3.7 管理员账号

演示环境只有**一个账号**：用户名固定 `admin`（表 `sys_user`，id=1，角色 `ADMIN`）。
口令以 **bcrypt 哈希**存储（`$2a$` 前缀、strength 10、长度 60），**不是明文**。

**查看当前口令**（`.env` 是唯一权威来源）：

```bash
grep '^TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD=' /opt/trustagent/.env
```

**改口令没有接口可用。** `UserAdminController` 只有三条路由——创建用户、改角色、改状态
（`backend/src/main/java/io/trustagent/user/UserAdminController.java:49-92`），
**没有"修改口令"**。所以只能直接写库，流程是：

```bash
# 1) 本地生成哈希（用后端同款的 BCryptPasswordEncoder，保证格式一致）
#    需要 JDK 21 + spring-security-crypto jar，见 scripts/tools/GenHash.java 的用法说明
javac -cp "$SPRING_SECURITY_CRYPTO_JAR" scripts/tools/GenHash.java
java  -cp "$SPRING_SECURITY_CRYPTO_JAR;." GenHash '<新口令>'   # 会先跑 4 项自检再输出哈希

# 2) 改库前先备份原哈希（关键：否则改坏了就登不进去，且无法还原）
docker exec trustagent-mysql mysql -u"$TRUSTAGENT_DB_USERNAME" -p"$TRUSTAGENT_DB_PASSWORD" \
  -D trustagent -N -e "SELECT password_hash FROM sys_user WHERE username='admin';"

# 3) 写入新哈希
docker exec trustagent-mysql mysql -u"$TRUSTAGENT_DB_USERNAME" -p"$TRUSTAGENT_DB_PASSWORD" \
  -D trustagent -e "UPDATE sys_user SET password_hash='<哈希>', updated_at=NOW() WHERE username='admin';"

# 4) 验证：能登进去 + 错误口令必须被拒（两项都要测，只测前者说明不了问题）
```

原哈希的备份路径：`/opt/trustagent/.local/backups/pre-password-change/`。
还原就是把那个文件里的值写回 `sys_user.password_hash`。

**三条容易踩的点：**

* 口令长度：`POST /api/users` 有 `@Size(min = 8)` 校验，但**直接写库不受此限制**。
  绕过的只是接口校验，不是安全边界——弱口令的风险一点没少。
* 哈希变更**不会**让已签发的 JWT 失效（本项目无令牌吊销机制），旧会话仍然有效直到过期。
* **演示用的弱口令（6 位纯数字）只允许存在于"仅 SSH 隧道访问"的私有模式下。**
  开启公网模式前**必须**换成强口令——6 位数字在公网上的爆破时间以秒计。
  公网检查清单见 [§7](#7-安全清单)。

---

## 4. 日常运维命令

### 三条最常用

```bash
./scripts/server/deploy.sh          # 启动 / 升级
./scripts/server/status.sh          # 查看状态
./scripts/server/stop.sh            # 停止（保留数据卷）
```

### 全部命令

| 命令 | 作用 |
| --- | --- |
| `init-server-env.sh` | 生成 `.env`（不覆盖已有） |
| `preflight.sh` | 部署前检查（只读） |
| `deploy.sh [--skip-backup]` | 发布 / 升级 |
| `status.sh [--record]` | 状态与资源；`--record` 追加一行到资源日志 |
| `smoke.sh` | 端到端冒烟 |
| `verify-stack.sh [--full-eval]` | 真实 MySQL / Redis 验证 |
| `backup.sh [--tag X] [--keep N]` | 备份 MySQL + Redis + `.env` |
| `restore.sh --from X --to-test` | 恢复到**临时库**（默认，安全） |
| `restore.sh --from X --to-live --yes-i-know` | 覆盖线上库（需先停服务） |
| `rollback.sh --list \| --to X \| --previous` | 回滚镜像版本 |
| `stop.sh [--down]` | 停止（数据卷始终保留） |
| `queue-retention.sh [--apply]` | 队列保留策略（默认 dry-run） |

### 资源观测

```bash
./scripts/server/status.sh --record     # 记录一行到 .local/server/resource-log.csv
```

CSV 字段：`timestamp, mem_total_mb, mem_avail_mb, swap_used_mb, load1, container_mem_mb, oom_events, restarts`

按「初始化 → 空闲 → 正常请求 → 批量评测 → 重启」的顺序各记录一次，
就能得到一份可对照的资源曲线。判定标准：

- `MemAvailable` 长期 < 200 MiB：**已在危险区间**，随时可能 OOM
- `oom_events > 0`：上限太小或存在泄漏，必须调整
- `restarts` 持续增长：崩溃循环，先查日志再谈优化
- `swap_used_mb` 持续 > 50 MiB：说明在靠换页运行，**这不是健康状态**

**出现持续低可用内存、OOM、重启循环、磁盘不足时，应停止测试并调整，而不是把参数放大硬扛。**

---

## 5. 备份与回滚

### 备份

```bash
./scripts/server/backup.sh --tag manual
```

产物在 `.local/backups/trustagent-<时间戳>[-tag]/`：

| 文件 | 内容 |
| --- | --- |
| `db.sql.gz` | MySQL 逻辑备份（`--single-transaction`，不锁表；不含系统库） |
| `redis-appendonly.tar.gz` | Redis AOF（先 `BGREWRITEAOF` 并等待完成再复制） |
| `env.bak` | `.env`（**含全部密钥**，权限 600） |
| `redis-queue-watermark.txt` | 备份时的队列水位（恢复后用来对照是否丢消息） |
| `META.txt` | 时间、镜像 tag、各文件 SHA256 |

Redis 也要备份的原因：Redis 里存着 `eval.jobs`。当前实现**没有**
"从 MySQL 的 PENDING 状态重新入队"的补偿路径，所以队列丢了任务就再也不会执行。

### 恢复

```bash
# 默认：恢复到临时库 trustagent_restore_test，绝不碰线上数据
./scripts/server/restore.sh --from trustagent-20260916-120000 --to-test

# 校验完就删掉临时库
./scripts/server/restore.sh --from trustagent-20260916-120000 --to-test --drop-after
```

**为什么默认是临时库**：恢复演练应该经常做（否则真出事时才发现备份是坏的），
而如果默认就覆盖线上，就没人敢练，备份也就永远没被验证过——
**一份没验证过的备份等于没有备份。**

覆盖线上库需要显式两条参数，且必须先停服务：

```bash
./scripts/server/stop.sh
./scripts/server/restore.sh --from <dir> --to-live --yes-i-know
```

### 回滚

```bash
./scripts/server/rollback.sh --list          # 看可用版本
./scripts/server/rollback.sh --previous      # 回到上一版
```

> **代码回滚 ≠ 数据库回滚。**
> Flyway 的迁移是单向的（没有 undo 脚本）。`rollback.sh` 只把镜像 tag 换回去，
> 数据库 schema 仍停在"新"的形态上。如果旧代码与新 schema 不兼容，
> 需要额外用迁移前的备份做 `restore.sh --to-live`。
> 这正是 `deploy.sh` 坚持"迁移前先备份"的原因：没有那份备份，回滚只能做一半。

Redis 队列同样不会回滚；旧版 Worker 可能不认识新版写入的消息字段，
这些任务会失败并进死信——这是可接受的失败方式（不会静默丢）。

---

## 6. 队列保留策略

`RedisEvalJobPublisher` 刻意**不做** `MAXLEN` 裁剪，理由是"按条数裁剪会丢掉未处理的任务"。
这个理由成立，但代价是 stream 无限增长；而 Redis 的 `noeviction` 意味着
增长到上限后**所有写操作都会失败**（包括限流与入队）。两个需求在
"只裁剪已确认的前缀"这一点上汇合：

```bash
./scripts/server/queue-retention.sh           # dry-run，只看现状
./scripts/server/queue-retention.sh --apply   # 实际裁剪
```

安全判据（脚本会打印它算出的裁剪点）：

- `L` = 消费组 `last-delivered-id`：大于它的条目可能从未投递
- `P` = PEL 里最小的一条 id：小于它的已投递条目必然都已 ACK
- 裁剪到 `min(L, P)`：未 ACK 的与未投递的都不在范围内

脚本在执行后会**再确认一次"未确认数没有被减少"**，若发现疑似误删会直接报错退出。

### 数据丢失窗口（诚实边界）

- AOF `appendfsync everysec` → **崩溃时最多丢最后 1 秒的写入**
- RDB 快照被关闭（`BGSAVE` 会 fork，在 96 MiB 容器里峰值内存可能翻倍）

所以本项目的投递语义是 **至少一次（at-least-once）**，不是"绝不丢消息"。
控制面用唯一索引 `(run_id, case_id, guard_enabled)` 吸收重复，
不对**外部副作用**承诺恰好一次执行。

---

## 7. 安全清单

部署后逐项确认：

- [ ] `.env` 权限 600；未被提交到 Git
- [ ] 数据库应用账号**不是** root
- [ ] `/internal/**` 在代理入口返回 404（`smoke.sh` 的 S1 项）
- [ ] `/internal/**` 在服务端仍有 `InternalTokenFilter` 强制校验（不是只靠代理）
- [ ] MySQL / Redis / 控制面 / 执行面在宿主机上无监听（`status.sh` 第 5 节）
- [ ] 未登录访问返回 401；普通用户访问管理员接口返回 403（`verify-stack.sh` C1/C2）
- [ ] 跨用户对象访问被拒绝（C4）
- [ ] 普通用户无法通过构造请求关闭防护（C6）
- [ ] 真实模型接入保持关闭（`TRUSTAGENT_LLM_PROVIDER=mock`）
- [ ] 公网模式下 swagger 与内部接口均被拒绝
- [ ] 确认 80/443 的占用者，公网模式不与现有服务冲突（见下）

### 这台机器**不是独占的**（部署前必须知道）

实测环境上同时跑着**其他项目**：

| 项 | 情况 | 影响 |
| --- | --- | --- |
| 邻居容器 `workbuddy2api` | 约 15.8 MiB，端口绑 `127.0.0.1:7863`（回环，未对公网） | 内存余量要把它算进去 |
| 邻居的 nginx | 监听 `0.0.0.0:80` 与 `0.0.0.0:443`（**公网**） | **公网模式下 TrustAgent 无法直接占用 80/443** |
| 邻居的 python 进程 | `127.0.0.1:7864` | 与本项目无冲突 |

两条结论，都会影响决策：

1. **内存预算不是"独占 1536 MiB"。** 实测：宿主 `MemTotal 1967 MiB`、
   TrustAgent 六容器约 **526 MiB**、邻居容器约 16 MiB，其余是系统、
   Docker 守护进程与邻居的 nginx。`preflight.sh` 用"MemAvailable < 900 MiB 就告警"
   来兜这个风险——**别把 1536 MiB 当成可用总量**。
2. **公网模式不能直接占 80/443。** 那两个端口已被现有 nginx 占用。
   可行路径是：给 TrustAgent 一个子域名，由**现有 nginx 反代**到
   `127.0.0.1:8090`，而不是自己再起一个抢 80/443 的 Caddy。
   在没确认这条路径之前，**保持私有模式**。

好消息：私有模式的隔离是成立的——TrustAgent 只绑 `127.0.0.1:8090`，
与邻居的公网 80/443 完全分离，双方互不影响。

### 关于 UFW / 安全组

**不要**只看 `ufw status` 就认为端口已隔离。Docker 自己写 iptables 规则，
经常绕过 UFW 的 `INPUT` 链，于是会出现"`ufw status` 显示 3306 被拒绝、
实际仍可连通"的情况。唯一可信的依据是**容器自己的绑定**
（`docker inspect` 的 `.NetworkSettings.Ports`），这正是 `status.sh` 第 5 节检查的内容，
也是低配编排用 `expose` 而非 `ports` 的原因——**宿主上根本不监听**是比"绑定地址正确"
更强的保证，它不依赖任何人后续不改配置。

### SSH 加固（人工操作，脚本不会自动改）

关闭密码登录前**必须先确认密钥登录已经可用**，否则会把自己锁在门外：

```bash
# 1) 先在另一个终端验证密钥登录确实可用（不要关掉当前会话）
ssh -i ~/.ssh/<your_key> <user>@<server> 'echo key-login-ok'

# 2) 再改配置
sudo sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/^#\?PubkeyAuthentication.*/PubkeyAuthentication yes/' /etc/ssh/sshd_config
sudo sshd -t            # 先做语法检查，别直接重启
sudo systemctl reload ssh
```

保留当前已登录的会话不要关，直到新会话用密钥登录成功为止。

---

## 8. 需要你完成的人工操作（本轮的阻塞项）

按优先级：

1. **改掉之前贴在对话里的服务器 root 口令**（它已进入对话历史）。
   顺手改成密钥登录 + 禁用密码登录，见第 7 节。
2. **配置 SSH 免密到目标服务器**（本轮的部署阻塞项）：
   ```bash
   # 生成专用密钥（不要用已有密钥，避免影响其他项目）
   ssh-keygen -t ed25519 -f ~/.ssh/trustagent_deploy -C trustagent-deploy
   ssh-copy-id -i ~/.ssh/trustagent_deploy.pub <user>@<server>

   # 建议在 ~/.ssh/config 里加别名，后续脚本直接用别名
   # Host trustagent-demo
   #     HostName <server>
   #     User <user>
   #     IdentityFile ~/.ssh/trustagent_deploy
   ```
   确认主机指纹后，先跑 `ssh trustagent-demo 'nproc && free -m && df -h /opt'`
   确认机器规格。
3. **恢复 Docker**：本机 Docker Desktop 报 `HCS_E_SERVICE_NOT_AVAILABLE`，
   需要启用 Hyper-V/WSL2 相关 Windows 功能并重启。恢复后才能构建镜像与跑真实组件验证。
4. **安装 `curl` 与 `jq`**（服务器上，冒烟脚本需要；安装系统软件不在本轮授权内）。
5. 确认 `/opt/trustagent` 是否已被其他项目占用（若占用，`preflight.sh` 会报冲突）。

---

## 9. 故障排查

| 现象 | 先看哪里 |
| --- | --- |
| 容器起不来 | `docker compose -f docker-compose.lowmem.yml logs --tail=120 <服务>` |
| 内存疑似不够 | `./scripts/server/status.sh` 第 2/3/4 节（上限是否生效、OOMKilled、重启次数） |
| 评测任务一直 PENDING | `status.sh` 第 6 节（PEL 积压）；`logs ai-worker` |
| 页面能打开但接口 401 | 是不是没走隧道？代理层没有额外认证，401 一定来自应用 |
| 改完口令登不进去 | §3.7；原哈希备份在 `/opt/trustagent/.local/backups/pre-password-change/` |
| 端口意外暴露 | `status.sh` 第 5 节；检查是否有人把 `ports:` 改成了 `8080:8080` |
| 磁盘满了 | `status.sh` 第 7 节；日志已限 10 MB × 3 每容器；用 `queue-retention.sh` 看队列 |

### 常见真实原因

- **MySQL 首次启动慢**：首次初始化需要 1~2 分钟，`deploy.sh` 给的健康检查窗口是 180 秒。
- **宿主重启后服务顺序**：`depends_on` 的条件只在 `compose up` 时生效，
  Docker 重启容器时不保证顺序。表现为控制面先起、连不上 MySQL、退出、被
  `restart: unless-stopped` 拉起，几轮后自愈。这是设计上的已知行为。
- **`Cannot load driver class: org.h2.Driver`**：说明 `spring-boot-maven-plugin` 的
  `<excludes>` 被写在了插件级 `<configuration>` 而不是 `repackage` execution 里，
  导致 dev 需要的 H2 被踢出运行类路径。见 `backend/pom.xml` 的注释。
- **脚本在服务器上行为异常，但 `cat` 看内容是好的**：很可能是有人用
  `sed -i "s/\r$//"` 去"修"行尾。这个表达式一旦在传输中丢掉那个反斜杠，就变成
  `s/r$//` —— **静默删掉每一行末尾的 `r`**，而 `sed` 不报任何错。真实症状：

  | 原本 | 被破坏后 | 后果 |
  | --- | --- | --- |
  | `UPDATE sys_user` | `UPDATE sys_use` | `Table 'trustagent.sys_use' doesn't exist` |
  | `require_docker` | `require_docke` | `command not found` |

  正确做法：**本地**就保证是 LF 行尾、上传后用 `sha256sum` 与本地比对，
  然后**原样执行**。不要用 sed 就地"修"——文件内容在传输中是字节正确的，
  加这一步只会引入一个静默破坏源。Windows ↔ Linux 之间传 `.sh`/`.ps1` 时尤其要注意。

---

## 10. 演示流程（3 分钟）

| 时间 | 操作 | 说明什么 |
| --- | --- | --- |
| 0:00 | `./scripts/server/status.sh` | 6 个容器、内存上限实际值、只有 web 绑回环 |
| 0:20 | 浏览器打开 `http://localhost:8090`（隧道已建） | 前端是构建产物，不是 dev server |
| 0:40 | 登录 → 创建 Agent 应用 | 认证走真实 JWT |
| 1:00 | Playground 输入"算一下 (128+472)*3" | 场景 A：`calculator` 被允许并真实执行 |
| 1:20 | 输入"发邮件通知所有用户升级完成" | 场景 B：`send_notification` 未授权，**执行前**被策略层拦下 |
| 1:40 | 输入"搜索项目文档里关于系统升级的内容" | 场景 C：检索到的文档内含注入指令，被检出 |
| 2:00 | 输入带合成手机号/邮箱/Key 的文本 | 场景 D：输入被脱敏，原文不出现在返回中 |
| 2:20 | 打开 Trace 详情页 | 每个工具调用的耗时与脱敏参数 |
| 2:40 | 审计链验证页 | 篡改可检测（**不是**不可篡改） |

---

## 11. 请求是怎么走完全程的（写给初学者）

一次 Playground 提问，按顺序经过这些环节：

**1. 浏览器 → Caddy（你的电脑 → SSH 隧道 → 服务器 127.0.0.1:8090）**
前端代码里**没有**后端地址，只用相对路径 `/api`。
好处是同一份构建产物可以部署到任何环境。Caddy 看到 `/api` 就转发给 `backend` 容器。

**2. Caddy 拒绝 `/internal/**`**
内部接口（Python 回调 Java 用的）在代理层直接返回 404。
服务端还会再校验一次 `X-Internal-Token`——两道都要，因为
"只靠代理拦截"在有人绕过代理时就完全裸奔，"只靠服务端"则意味着公网上存在
一个拿着共享密钥就能访问的端点。

**3. Java 控制面：认证 → 限流 → 参数校验 → 生成 Trace ID**
先用 `Authorization: Bearer <JWT>` 解析出用户与角色；
再用 Redis 的滑动窗口限流；然后把输入长度等参数校验一遍。
Trace ID 从这里生成，之后所有日志、数据库记录、Python 侧日志都用同一个值——
这样"用户这次操作用的是哪个 ID"和"后端日志里的 ID"能对上。

**4. Java 把请求交给 Python 执行面**（`POST /v1/agent/run`，带 `X-Internal-Token` 与 `X-Trace-Id`）

**5. Python：输入安全检查（在模型之前）**
先检测 **提示注入**：直接注入（"忽略上面的规则"）、以及**间接注入**——
后者的顺序很关键：先检索文档 → 文档内容里可能夹带指令 → 再检测。
所以间接注入只有在"工具返回了内容"之后才可能被发现。
同时检测 **PII**（手机号/邮箱/身份证/银行卡/API Key 样式），把命中的部分替换成掩码，
日志与返回值里都只留掩码后的版本。

**6. MockLLM 决定是否调用工具**
它按确定性规则工作（同样的输入永远给同样的输出），所以评测结果可复现。

**7. 工具调用的安全检查（在工具真正执行之前）**
这一步是"策略层"而不是"提示词提醒"。逐个检查：这个工具在允许列表里吗？
调用次数超上限了吗？参数符合 JSON Schema 吗？
有没有命中"敏感参数名禁止规则"？有副作用需不需要确认？
**任何一条不过，工具就不会被执行**，并记录一条安全事件。

**8. 工具执行**
四个内置工具都是安全的模拟实现：`calculator` 只做算术；`search_local_docs`
只读项目内的演示文档；`query_traffic_metrics` 查合成交通指标；
`send_notification` 只往本地 Mock Outbox 写一行，不发送任何真实消息。
它们都不能执行 shell、访问任意 URL、读写任意文件。

**9. 输出安全检查**
检查模型输出里有没有：敏感信息、系统提示词片段、未经验证的 URL、
可能被前端执行的 HTML 或脚本。前端只以**文本**方式展示模型输出，不做 HTML 渲染。

**10. Java 保存记录**
写 Trace 摘要、安全事件、审计记录。审计记录里保存上一条的哈希，
当前哈希由规范化后的字段 + 前序哈希 + 时间算出——任何一条被改动，
验证接口就能定位到第一个异常位置。

**11. 评测任务走的是另一条路：Redis 队列**
批量评测不是同步 HTTP 调用。`POST /api/eval/runs` 只在 MySQL 里建一条 `PENDING` 记录，
然后往 Redis 的 `eval.jobs` Stream 写一条**只带引用**的消息（不含用例文本）。
Python Worker 用消费组读走消息，回控制面拉取当时生效的那份策略快照，
逐条执行，分批回调结果，全部完成后才 `XACK`。

为什么用队列而不是同步跑：一次 100 条评测要几分钟，同步 HTTP 会占住线程、
中途网络抖动整批白跑、也没法断点续跑。

失败与重复怎么处理：

- 处理成功才 `XACK`；失败就不 ACK，消息留在 PEL 里
- 超过认领超时后，`XAUTOCLAIM` 把它交给还活着的 Worker 重新执行
- 投递次数超过上限 → 转入死信队列 `eval.jobs.dlq`，并回调控制面标记失败
- 重复投递是允许的（**至少一次**语义），靠唯一索引
  `(run_id, case_id, guard_enabled)` 吸收重复，不会产生重复结果行，完成计数也不会被重复累加

**12. 控制面不采信执行面上报的指标**
Worker 上报的指标会被控制面**按逐条结果自己复算一遍**再入库。
对不上就记一条 `METRICS_MISMATCH` 事件。这是整条评测链路可信的基础：
如果执行面说"攻击成功率 0.9"就直接存 0.9，那评测结果就没有独立验证。

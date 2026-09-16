# k6 压测脚本

> **本项目不自动执行压测。** 脚本只提供，是否运行由你决定
> （高并发压测会对本机与下游服务造成压力，属于需要明确授权的重操作）。

## 前提

安装 k6：<https://grafana.com/docs/k6/latest/set-up/install-k6/>

**必须先调整限流额度**，否则压测会在几秒内被限流拦住，测出来的是限流而不是性能：

```powershell
# 例：把 chat 提到 10000 次/分钟，login 提到 1000 次/分钟
$env:TRUSTAGENT_RL_CHAT_LIMIT="10000"
$env:TRUSTAGENT_RL_LOGIN_LIMIT="1000"
# 然后重启控制面
pwsh scripts/start-backend-dev.ps1
```

原因：默认额度是 chat 30 次/分钟、login 10 次/分钟。压测时 429 会占满结果，
你看到的"P95 延迟"其实是限流器的响应时间。

## 两套脚本

| 脚本 | 测什么 | 何时用 |
| --- | --- | --- |
| `control-plane.js` | **只测 Java 控制接口**（登录、应用列表、读策略、读审计链） | 想单独评估控制面自身的吞吐与延迟 |
| `full-chain.js` | **完整链路**（`/api/apps/{id}/chat`，经 Python 执行面 + MockLLM + 工具） | 想评估端到端表现 |

两者的差别很重要：`control-plane.js` 的结果**不能**用来推断完整链路的性能，
因为完整链路还包含一次跨服务 HTTP 调用与整个安检流水线。

## 运行

```bash
# 准备：先执行 scripts/init-dev-env.ps1 生成 .env，并导出脚本需要的变量
k6 run k6/control-plane.js -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=...
k6 run k6/full-chain.js     -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=... -e APP_ID=1
```

`PASSWORD` 从 `.env` 里的 `TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD` 取，
**不要把它写进任何文件**。

## 阈值

脚本里给了保守的 `thresholds`，但请注意：

* **这些阈值不是"目标"，而是"明显异常"的下限**。本项目从未在本机跑过压测，
  因此没有任何基线数据可以定出合理阈值；
* 首次运行时建议先用 `--vus 1 --duration 10s` 跑一遍，拿到基线，
  再据此调整阈值并写进 `docs/BENCHMARK.md`。

## 结果解读的注意事项

* **MockLLM 是进程内确定性逻辑，没有网络调用、没有模型推理**。
  因此延迟数字不代表接入真实模型后的表现，**不能作为任何性能声明**；
* 控制面用 H2 文件库时，写入路径与 MySQL 不同；
* 单实例、单机运行，结果不含网络与负载均衡的影响。

一句话：这套脚本的用途是"验证系统在压力下的行为是否可预期"（有没有连接池耗尽、
有没有限流误伤、错误率是否随并发上升），**不是产出性能指标**。

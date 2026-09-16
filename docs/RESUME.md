# 简历描述草稿

> 所有数字都来自仓库里的实测结果，可复现（`docs/BENCHMARK.md`）。
> **不要写这里没有的数字**——面试官追问一句"这个数怎么来的"就露馅了。

---

## 一、项目一行描述

**中文**

> **TrustAgent｜AI Agent 安全网关、自动化评测与审计平台**
> 独立设计并实现的双服务系统：Java(Spring Boot) 控制面负责认证/授权/策略/审计/评测编排，
> Python(FastAPI) 执行面负责 Agent 编排与内容安检；实现提示注入检测、PII 脱敏、
> 工具调用前置策略拦截、篡改可检测（tamper-evident）审计哈希链与跨语言异步评测。

**English**

> **TrustAgent — Security Gateway, Automated Evaluation & Audit Platform for AI Agents**
> A two-service system: a Java (Spring Boot) control plane for auth, RBAC, policy, audit and
> evaluation orchestration, plus a Python (FastAPI) execution plane for agent orchestration and
> content guardrails. Includes prompt-injection detection, PII masking, pre-execution tool
> policy enforcement, a tamper-evident audit hash chain, and cross-language async evaluation.

---

## 二、可直接使用的简历条目

按"投递岗位"挑 3~5 条，不要全放。

### 通用 / Java 后端方向

- 设计并实现 Agent 安全网关的控制面（Spring Boot 3.5 + Spring Security + MyBatis-Plus + Flyway），
  实现 JWT 认证、RBAC（3 角色 15 权限点）、策略版本乐观锁、Redis 滑动窗口限流与幂等任务创建，
  **Java 侧 82 项单元/集成测试全部通过**（H2 内存库，不依赖外部服务）。
- 实现 Stripe 风格幂等创建：唯一索引 + 请求指纹区分"重放（200）"与"冲突（409）"，
  **缺 `Idempotency-Key` 直接 400 而非静默降级**；并把"入队"放在事务之外，
  避免回滚后队列里留下幽灵任务。
- 实现篡改可检测（tamper-evident）的审计哈希链（规范化序列化 + 前序哈希 + 定位首个异常 seq），
  支持 `RECORD_HASH_MISMATCH / SEQ_GAP / PREV_HASH_MISMATCH` 三类异常定位；
  在文档中明确它是 **tamper-evident 而非不可篡改**，不宣称区块链。

### AI 应用 / Agent 方向

- 实现 Agent 执行面（FastAPI + Pydantic），把"安检 → 模型决策 → 工具前策略校验 → 执行 →
  输出安检"串成一条链路；**策略拦截发生在工具真正执行之前**，
  实测 15 条工具滥用用例中 **10 条由工具白名单在执行前拦下**，证明安全不依赖模型自觉。
- 实现可解释的提示注入检测：规则表 + 文本标准化多视图（去零宽字符、NFKC、base64/hex 解码），
  **不改写原文以避免误报**；100 条合成评测集上直接注入拦截 13/15、间接注入 10/10、编码混淆 10/10。
- 提供统一 `LLMProvider` 接口与确定性 MockLLM（无需任何 API Key、不下载模型权重），
  真实模型通过 OpenAI 兼容接口按环境变量接入。

### 大模型评测方向

- 搭建跨语言评测链路：控制面投递任务 → 执行面消费并逐条执行 → 回调逐条结果 →
  **控制面从逐条结果独立复算指标并与上报值逐键比对（容差 1e-6）**，不一致落
  `METRICS_MISMATCH` 安全事件。实测两侧指标完全一致。
- 设计 100 条合成评测集（8 类风险）与对照实验：**防护开启 vs 关闭**，
  实测攻击成功率 **5.0% → 100%**、敏感信息泄露率 **40% → 100%**；
  逐条归因出 3 条漏报（规则库同义动词覆盖不足）与 4 条误报（演示语料刻意投毒）的具体原因。
- 修复一处**指标定义缺陷**：通过 `benignPassRate(0.65)` 与 `taskSuccessRate(0.94)` 的自相矛盾
  定位到"工具名匹配却忽略整次请求被拦"的判定漏洞；并发现"用被测组件的检测器衡量被测系统的
  泄露率"是循环论证，改为评测台独立口径后，泄露率从虚低的 0 暴露为真实的 40%。

### 数据安全 / AI 安全方向

- 实现工具调用策略执行点：白名单、调用次数上限、参数 JSON Schema、域名白名单、
  敏感参数规则、副作用确认、超时与输入长度，全部在动作发生前强制校验。
- 实现输入/输出双向内容安检：PII 识别与脱敏、系统提示词泄露、危险 HTML 转义、
  白名单外链接；前端**禁止 `v-html`** 并以 CSP 兜底，CI 里加了强制检查。

### 工程化 / 全栈

- 建立可复现的本地开发与验证链路：跨平台启动脚本 + Makefile + `docker-compose` +
  端到端演示脚本（6 个场景自动断言）；前端 Vue 3 + TS 完成 9 个页面并接入真实接口，
  `npm run build`（含 `vue-tsc` 类型检查）通过。
- 在 CI 中加入两条项目特有的安全门禁：**禁止密钥文件入库**、**禁止前端出现 `v-html`**；
  并断言"生产 fat jar 中不含 H2 驱动"（H2 只用于 dev/test）。

---

## 三、面试时可以用数据支撑的点

| 说法 | 支撑数据 / 位置 |
| --- | --- |
| 策略层真的能挡住越权工具调用 | 15/15 工具滥用被拦，其中 10 条由白名单在执行前拦下（`docs/BENCHMARK.md` §4） |
| 防护有效 | ASR 5.0% vs 基线 100%；PII 泄露率 40% vs 100% |
| 指标不是编的 | 控制面独立复算 + `metricsMismatch=none` + 报告输出 tp/fp/fn/tn 让人能自查 |
| 我知道自己的检测边界 | 3 条漏报的具体 prompt 与原因；4 条 PII 泄露的形态（`docs/BENCHMARK.md` §5） |
| 我知道评测本身的陷阱 | 3 个"用例写偏但指标看起来一样"的实例（`datasets/README.md` 末节） |
| 审计是 tamper-evident | 改一条记录后 `firstBrokenSeq` 定位到该条；同时说明可被整体重算（`docs/SECURITY_LIMITATIONS.md` §1） |

---

## 四、被问到"这个项目有什么不足"时的回答框架

1. **先分级**：数据层面（合成数据）> 实现层面（Redis 队列未在本机跑通端到端、
   真实 MySQL 未验证）> 能力层面（PII 检测召回不足、同形异义字不映射）> 工程层面
   （无监控告警、无密钥管理、无多实例验证）。
2. **再给理由**：哪些是刻意的范围控制（K8s/Kafka/ES 属于阶段4），
   哪些是能力边界（规则检测注定追赶同义改写），哪些是我认为的**真实缺陷**
   （PII 检测器认不出的形态会导致真实泄露，已实测并记录）。
3. **最后给下一步**：按 `docs/ROADMAP.md` 的阶段划分说清优先级。

**不要**把"没做"说成"做了但没细讲"，也不要说"时间不够"——
说清楚"我判断它不属于当前阶段的目标，理由是什么"更有说服力。

---

## 五、这个项目**不适合**写在简历里的说法

以下说法会被追问到崩，且与实现不符：

* ❌ "实现了生产级 AI 安全网关" —— 定位是教学/作品原型，`SECURITY_LIMITATIONS.md` 写得很清楚；
* ❌ "防护准确率 99%" —— 实测精确率 93.4%、召回率 95.0%，且是合成数据；
* ❌ "QPS 达到 xxx" —— 从未跑过压测，k6 脚本只提供未执行；
* ❌ "支持高并发/分布式部署" —— 限流的内存实现只保证单实例正确，未做多实例验证；
* ❌ "不可篡改审计" —— 是 tamper-evident，拥有数据库写权限者可重算整条链。

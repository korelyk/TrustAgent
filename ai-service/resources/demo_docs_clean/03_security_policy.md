# 安全策略字段说明

每个 Agent 应用有一份生效策略，字段含义如下。

| 字段 | 含义 | 默认值 |
| --- | --- | --- |
| `guardEnabled` | 总开关。关闭后所有检查跳过，仅用于"防护关闭"对照评测 | true |
| `maxToolCalls` | 单次任务最多工具调用次数 | 3 |
| `maxInputLength` | 单次输入最大字符数 | 4000 |
| `taskTimeoutMs` | 单次任务超时 | 15000 |
| `domainWhitelist` | 允许出现的域名（作用于工具参数与输出 URL） | docs.trustagent.local |
| `deniedParamRules` | 禁止出现在工具参数中的字段名 | password, token, secret, api_key |
| `confirmSideEffects` | 有副作用的工具是否需要人工确认 | true |
| `injectionDetectionEnabled` | 是否检测提示注入 | true |
| `piiMaskingEnabled` | 是否检测并脱敏敏感信息 | true |
| `outputGuardEnabled` | 是否检查模型输出 | true |

## 为什么要把它做成"数据"而不是"代码里的常量"

1. 不同应用的风险等级不同：查询文档的应用和能发通知的应用不该共用一套限制；
2. 策略变更需要可审计：每次修改都会递增 `version` 并写审计记录；
3. 策略是执行面必须遵守的**硬约束**：执行面把它当输入数据使用，
   因此执行面本身不需要数据库权限，减少一个信任边界。

"""TrustAgent Python 执行面（ai-service）。

职责：LLM 调用与 Agent 编排、输入/输出安全检查、工具调用前的策略强制校验、批量评测 Worker。
它 **不直连数据库**：所有持久化都通过回调 Java 控制面的 /internal 接口完成。
"""

__version__ = "0.1.0"

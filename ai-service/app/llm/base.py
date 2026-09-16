"""统一的 LLM 提供方接口。

设计目标：**业务逻辑不绑定任何模型框架**。

* ``MockLLMProvider``：默认实现，纯确定性，不需要网络与 API Key，单测与演示都用它。
* ``OpenAICompatibleProvider``：选配，通过 Base URL / API Key / Model 三个环境变量接入
  任何兼容 ``/chat/completions`` 的服务。

如果想用 LangGraph/LangChain 做编排，做法是**再加一个 LLMProvider 实现**或用它们的
tool-calling 适配器包一层，而不是把项目逻辑写进框架的 graph 里——这样框架升级不会波及业务代码。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable


@dataclass(frozen=True)
class ToolCallRequest:
    tool_name: str
    arguments: dict[str, Any] = field(default_factory=dict)
    reasoning: str = ""


@dataclass(frozen=True)
class LlmDecision:
    """模型的一次决策：要么请求调用工具，要么给出最终回答。"""

    kind: str  # tool_call | final
    text: str = ""
    tool_call: ToolCallRequest | None = None
    model: str = "unknown"

    @property
    def is_tool_call(self) -> bool:
        return self.kind == "tool_call" and self.tool_call is not None


@runtime_checkable
class LLMProvider(Protocol):
    name: str

    def decide(self, *, system_prompt: str | None,
               user_prompt: str,
               context: str | None,
               available_tools: list[str],
               tool_schemas: dict[str, str],
               history: list[dict[str, Any]]) -> LlmDecision:
        """返回一次决策。``history`` 里包含之前的工具调用与结果。"""
        ...

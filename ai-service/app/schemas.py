"""执行面的请求/响应模型。

命名约定：对外 JSON 使用 camelCase，与 Java 控制面对齐；Python 内部用 snake_case。
用 Pydantic 的 alias_generator 自动转换，避免两边手写字段映射表（最容易出错的地方）。
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


def _to_camel(name: str) -> str:
    head, *tail = name.split("_")
    return head + "".join(word.capitalize() for word in tail)


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=_to_camel,
        populate_by_name=True,
        extra="ignore",
    )


# ---------------------------------------------------------------- Agent 请求/响应


class ToolPolicy(CamelModel):
    """由控制面下发的安全策略。执行面把它当**数据**用，不自己查库。"""

    guard_enabled: bool = True
    max_tool_calls: int = 3
    max_input_length: int = 4000
    task_timeout_ms: int = 15000
    domain_whitelist: list[str] = Field(default_factory=list)
    denied_param_rules: list[str] = Field(default_factory=list)
    confirm_side_effects: bool = True
    injection_detection_enabled: bool = True
    pii_masking_enabled: bool = True
    output_guard_enabled: bool = True


class AgentRunRequest(CamelModel):
    app_id: int | None = None
    app_code: str | None = None
    system_prompt: str | None = None
    prompt: str
    context: str | None = None
    allowed_tools: list[str] = Field(default_factory=list)
    tool_schemas: dict[str, str] = Field(default_factory=dict)
    policy: ToolPolicy = Field(default_factory=ToolPolicy)


class ToolCallPayload(CamelModel):
    tool_name: str
    allowed: bool
    deny_reason: str | None = None
    arguments_masked: str | None = None
    result_masked: str | None = None
    latency_ms: int = 0


class GuardEventPayload(CamelModel):
    event_type: str
    severity: str
    detector: str
    rule_id: str | None = None
    evidence_masked: str | None = None
    action_taken: str


class AgentRunResponse(CamelModel):
    trace_id: str
    status: str
    guard_decision: str
    guard_categories: list[str] = Field(default_factory=list)
    input_masked: str
    output_masked: str
    tool_calls: list[ToolCallPayload] = Field(default_factory=list)
    security_events: list[GuardEventPayload] = Field(default_factory=list)
    model: str
    latency_ms: int


# ---------------------------------------------------------------- 安检调试接口


class InspectRequest(CamelModel):
    text: str
    source: str = "input"  # input | tool_output | model_output
    system_prompt: str | None = None


class InspectResponse(CamelModel):
    decision: str
    categories: list[str] = Field(default_factory=list)
    masked_text: str
    events: list[GuardEventPayload] = Field(default_factory=list)
    normalized_transforms: list[str] = Field(default_factory=list)


# ---------------------------------------------------------------- 工具


class ToolDescriptor(CamelModel):
    tool_name: str
    description: str
    side_effect: bool
    param_schema: dict[str, Any]


class ToolListResponse(CamelModel):
    tools: list[ToolDescriptor]
    version: str

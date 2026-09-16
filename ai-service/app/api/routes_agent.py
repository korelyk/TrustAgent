"""执行面接口：Agent 运行、安检调试、工具清单。

请求头 ``X-Trace-Id`` 由 Java 控制面透传；缺失时执行面自己生成，
保证即使有人直接调这个接口，日志与响应里也有可追踪的 traceId。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Header, Request

from app.agent.orchestrator import AgentOrchestrator
from app.api.deps import verify_internal_token
from app.guardrails.pipeline import GuardPipeline
from app.observability.trace import set_trace_id
from app.schemas import (
    AgentRunRequest,
    AgentRunResponse,
    InspectRequest,
    InspectResponse,
    ToolListResponse,
    ToolPolicy,
)
from app.tools.registry import REGISTRY_VERSION, describe

router = APIRouter()


def _resolve_trace_id(x_trace_id: str | None) -> str:
    return set_trace_id(x_trace_id)


@router.post("/v1/agent/run", response_model=AgentRunResponse, tags=["agent"],
             dependencies=[Depends(verify_internal_token)])
def run_agent(payload: AgentRunRequest, request: Request,
              x_trace_id: str | None = Header(default=None, alias="X-Trace-Id")) -> AgentRunResponse:
    trace_id = _resolve_trace_id(x_trace_id)
    orchestrator: AgentOrchestrator = request.app.state.orchestrator
    response = orchestrator.run(payload)
    # traceId 由这里统一填充，保证与日志、与 Java 控制面用的是同一个值
    return response.model_copy(update={"trace_id": trace_id})


@router.post("/v1/guard/inspect", response_model=InspectResponse, tags=["agent"],
             dependencies=[Depends(verify_internal_token)])
def inspect(payload: InspectRequest, request: Request,
            x_trace_id: str | None = Header(default=None, alias="X-Trace-Id")) -> InspectResponse:
    _resolve_trace_id(x_trace_id)
    pipeline: GuardPipeline = request.app.state.pipeline
    policy = ToolPolicy()  # 调试接口使用默认策略（全部检测开启）
    if payload.source == "tool_output":
        decision = pipeline.inspect_tool_output(payload.text, policy, payload.system_prompt)
    elif payload.source == "model_output":
        decision = pipeline.inspect_output(payload.text, policy, payload.system_prompt)
    else:
        decision = pipeline.inspect_input(payload.text, policy, payload.system_prompt)
    return InspectResponse(
        decision=decision.decision,
        categories=decision.categories,
        masked_text=decision.text,
        events=decision.events,
        normalized_transforms=list(decision.transforms),
    )


@router.get("/v1/tools", response_model=ToolListResponse, tags=["tools"],
            dependencies=[Depends(verify_internal_token)])
def list_tools(request: Request) -> ToolListResponse:
    return ToolListResponse(tools=describe(request.app.state.tools), version=REGISTRY_VERSION)


@router.get("/internal/config", tags=["internal"],
            dependencies=[Depends(verify_internal_token)])
def internal_config(request: Request) -> dict:
    settings = request.app.state.settings
    return {
        "llmProvider": settings.llm_provider,
        "llmModel": settings.llm_model,
        "evalWorkerEnabled": settings.eval_worker_enabled,
        "evalConsumerName": settings.eval_consumer_name,
        "hardMaxInputLength": settings.hard_max_input_length,
        "hardMaxToolCalls": settings.hard_max_tool_calls,
        "registryVersion": REGISTRY_VERSION,
    }

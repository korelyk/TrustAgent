"""执行面运行时装配。

抽出来的唯一原因：**API 进程与评测 Worker 需要完全相同的编排器**。

如果两处各自装配，很容易出现"API 用的是 A 版策略校验、Worker 用的是 B 版"这类漂移——
而评测的全部意义就是给出与线上一致的结果。所以装配只能有一处。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.agent.orchestrator import AgentOrchestrator
from app.config import Settings
from app.guardrails.injection import InjectionDetector
from app.guardrails.output_guard import OutputGuard
from app.guardrails.pii import PiiDetector
from app.guardrails.pipeline import GuardPipeline
from app.llm.factory import build_provider
from app.tools.registry import build_outbox, build_registry


@dataclass(frozen=True)
class Runtime:
    outbox: Any
    tools: dict[str, Any]
    pipeline: GuardPipeline
    orchestrator: AgentOrchestrator


def build_runtime(settings: Settings) -> Runtime:
    """构建工具注册表、安检流水线与编排器。纯内存操作，不做网络调用、不加载模型。"""
    outbox = build_outbox(settings)
    tools = build_registry(settings, outbox)
    pipeline = GuardPipeline(
        injection=InjectionDetector(),
        pii=PiiDetector(),
        output_guard=OutputGuard(),
    )
    orchestrator = AgentOrchestrator(
        llm=build_provider(settings),
        pipeline=pipeline,
        registry=tools,
    )
    return Runtime(outbox=outbox, tools=tools, pipeline=pipeline, orchestrator=orchestrator)

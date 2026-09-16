"""工具注册表。

执行面只认识这 4 个工具；控制面下发的策略决定"这一次允许用哪些"。
新增工具只需实现 ``Tool`` 协议并在这里注册，同时在
``backend/.../app/ToolCatalog.java`` 里补上名字，保持两侧一致。
"""

from __future__ import annotations

from typing import Any

from app.config import Settings
from app.schemas import ToolDescriptor
from app.tools.base import Tool
from app.tools.calculator import CalculatorTool
from app.tools.outbox import MockOutbox, SendNotificationTool
from app.tools.query_traffic_metrics import QueryTrafficMetricsTool
from app.tools.search_local_docs import DocumentStore, SearchLocalDocsTool

REGISTRY_VERSION = "1.0.0"


def build_outbox(settings: Settings) -> MockOutbox:
    return MockOutbox(file_path=settings.outbox_file if settings.outbox_file else None)


def build_registry(settings: Settings, outbox: MockOutbox | None = None) -> dict[str, Tool]:
    store = DocumentStore(settings.demo_docs_dir)
    tools: list[Tool] = [
        CalculatorTool(),
        SearchLocalDocsTool(store, top_k=settings.doc_search_top_k,
                            snippet_max_length=settings.doc_snippet_max_length),
        QueryTrafficMetricsTool(),
        SendNotificationTool(outbox or build_outbox(settings)),
    ]
    return {tool.name: tool for tool in tools}


def describe(registry: dict[str, Tool]) -> list[ToolDescriptor]:
    return [
        ToolDescriptor(
            tool_name=tool.name,
            description=tool.description,
            side_effect=tool.side_effect,
            param_schema=tool.param_schema,
        )
        for tool in registry.values()
    ]


def known_tool_names() -> list[str]:
    """不依赖 Settings 的工具名清单（供参数校验与测试使用）。"""
    return [
        CalculatorTool.name,
        SearchLocalDocsTool.name,
        QueryTrafficMetricsTool.name,
        SendNotificationTool.name,
    ]


def parse_schema_overrides(tool_schemas: dict[str, str]) -> dict[str, Any]:
    """控制面可能下发自定义参数 Schema（覆盖默认）。解析失败则忽略，使用默认。"""
    import json

    overrides: dict[str, Any] = {}
    for name, raw in (tool_schemas or {}).items():
        if not raw:
            continue
        try:
            overrides[name] = json.loads(raw)
        except json.JSONDecodeError:
            continue
    return overrides

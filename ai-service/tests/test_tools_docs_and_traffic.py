"""检索工具、交通指标工具、通知工具测试。"""

from __future__ import annotations

from datetime import date

import pytest

from app.config import Settings
from app.guardrails.events import PROMPT_INJECTION_INDIRECT
from app.guardrails.pipeline import GuardPipeline
from app.schemas import ToolPolicy
from app.tools.base import ToolContext
from app.tools.outbox import MockOutbox, SendNotificationTool
from app.tools.query_traffic_metrics import QueryTrafficMetricsTool
from app.tools.search_local_docs import DocumentStore, SearchLocalDocsTool


class TestSearchLocalDocs:
    @pytest.fixture
    def store(self, settings: Settings) -> DocumentStore:
        return DocumentStore(settings.demo_docs_dir)

    @pytest.fixture
    def tool(self, store: DocumentStore) -> SearchLocalDocsTool:
        return SearchLocalDocsTool(store, top_k=3, snippet_max_length=1200)

    def test_loads_demo_documents(self, store: DocumentStore):
        names = {document.name for document in store.documents}
        assert "01_project_overview.md" in names
        assert "02_traffic_dataset.md" in names

    def test_finds_traffic_document(self, tool: SearchLocalDocsTool):
        result = tool.invoke({"query": "交通指标数据"}, ToolContext())
        assert result.ok is True
        assert "traffic" in result.value.lower() or "交通" in result.value

    def test_marks_result_as_untrusted(self, tool: SearchLocalDocsTool):
        result = tool.invoke({"query": "项目说明"}, ToolContext())
        assert result.untrusted is True

    def test_poisoned_document_is_retrievable_and_flagged(self, tool: SearchLocalDocsTool,
                                                          pipeline: GuardPipeline,
                                                          default_policy: ToolPolicy):
        """场景C：检索到的文档里含注入指令，必须被二次安检识别。"""
        result = tool.invoke({"query": "系统升级 维护通知"}, ToolContext())
        assert result.ok is True

        decision = pipeline.inspect_tool_output(result.value, default_policy, system_prompt="你是助手")
        assert decision.blocked is True
        assert PROMPT_INJECTION_INDIRECT in decision.categories

    def test_not_found_returns_ok_with_message(self, tool: SearchLocalDocsTool):
        result = tool.invoke({"query": "zzzzz-nonexistent-topic"}, ToolContext())
        assert result.ok is True
        assert "未找到" in result.value

    def test_missing_query(self, tool: SearchLocalDocsTool):
        result = tool.invoke({}, ToolContext())
        assert result.ok is False

    def test_top_k_is_clamped(self, tool: SearchLocalDocsTool):
        result = tool.invoke({"query": "说明", "topK": 999}, ToolContext())
        assert result.ok is True


class TestQueryTrafficMetrics:
    @pytest.fixture
    def tool(self) -> QueryTrafficMetricsTool:
        # 注入固定时钟，保证跨日期运行结果不变
        return QueryTrafficMetricsTool(today_provider=lambda: date(2026, 9, 15))

    def test_deterministic_for_same_input(self, tool: QueryTrafficMetricsTool):
        args = {"metric": "avg_speed", "road": "二环路", "date": "2026-09-14"}
        first = tool.invoke(args, ToolContext())
        second = tool.invoke(args, ToolContext())
        assert first.value == second.value
        assert "平均车速" in first.value

    def test_relative_date_resolution(self, tool: QueryTrafficMetricsTool):
        yesterday = tool.invoke({"metric": "avg_speed", "road": "二环路", "date": "昨天"}, ToolContext())
        explicit = tool.invoke({"metric": "avg_speed", "road": "二环路", "date": "2026-09-14"}, ToolContext())
        assert yesterday.value == explicit.value

    def test_weekend_is_less_congested(self, tool: QueryTrafficMetricsTool):
        # 2026-09-12 是周六，2026-09-15 是周二
        weekend = tool.invoke({"metric": "avg_speed", "road": "二环路", "date": "2026-09-12"}, ToolContext())
        weekday = tool.invoke({"metric": "avg_speed", "road": "二环路", "date": "2026-09-15"}, ToolContext())
        assert weekend.value != weekday.value

    @pytest.mark.parametrize("metric", ["avg_speed", "volume", "congestion_index"])
    def test_all_metrics_supported(self, tool: QueryTrafficMetricsTool, metric: str):
        result = tool.invoke({"metric": metric, "road": "三环路", "date": "2026-09-14"}, ToolContext())
        assert result.ok is True

    def test_rejects_unknown_road(self, tool: QueryTrafficMetricsTool):
        result = tool.invoke({"metric": "avg_speed", "road": "不存在的路"}, ToolContext())
        assert result.ok is False

    def test_rejects_unknown_metric(self, tool: QueryTrafficMetricsTool):
        result = tool.invoke({"metric": "hack", "road": "二环路"}, ToolContext())
        assert result.ok is False

    def test_result_is_marked_as_synthetic(self, tool: QueryTrafficMetricsTool):
        result = tool.invoke({"metric": "volume", "road": "滨海大道", "date": "2026-09-14"}, ToolContext())
        assert "合成数据" in result.value


class TestSendNotification:
    @pytest.fixture
    def tool(self, tmp_path) -> SendNotificationTool:
        return SendNotificationTool(MockOutbox(file_path=tmp_path / "outbox.jsonl"))

    def test_writes_to_mock_outbox_only(self, tool: SendNotificationTool, tmp_path):
        result = tool.invoke({"channel": "email", "recipient": "all-users", "message": "维护通知"},
                             ToolContext())
        assert result.ok is True
        assert "Mock Outbox" in result.value
        assert "未发送真实消息" in result.value

        written = (tmp_path / "outbox.jsonl").read_text(encoding="utf-8")
        assert "all-users" in written
        assert "模拟投递" in written

    def test_side_effect_flag(self):
        assert SendNotificationTool.side_effect is True

    @pytest.mark.parametrize("arguments", [
        {"channel": "email"},
        {"channel": "email", "recipient": "a"},
        {"recipient": "a", "message": "b"},
    ])
    def test_requires_all_arguments(self, tool: SendNotificationTool, arguments: dict):
        assert tool.invoke(arguments, ToolContext()).ok is False

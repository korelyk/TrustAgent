"""MockLLM 提出的工具参数必须通过**工具自己的** JSON Schema。

### 为什么值得单独写一个测试文件

这两个模块是分开写的：``app/llm/mock.py`` 负责"猜用户想调用哪个工具、参数填什么"，
``app/tools/*.py`` 各自持有自己的 ``param_schema``。
两者之间没有编译期约束，**写错了不会报错，只会让策略层把正常请求拦下来**。

真实踩过的坑：``MockLLM._extract_road`` 曾把 "二环路" 截成 "二环"，
而 ``query_traffic_metrics`` 的 ``road`` 是枚举、只接受 "二环路"。
后果是两条 NORMAL 用例被记成"误拦"，让人以为是策略太严——
实际是 Mock 的参数非法，策略层拒绝它完全正确。

这个测试把"两者必须一致"变成可执行的断言，并且**遍历工具目录里的每一条道路**，
将来往 ``ROADS`` 里加路名时也会被覆盖到。
"""

from __future__ import annotations

import jsonschema
import pytest

from app.llm.mock import MockLLMProvider
from app.tools.query_traffic_metrics import ROADS

#: 覆盖每个工具的典型触发语句。意图识别是关键词式的，因此每类至少两条不同措辞。
PROMPTS_BY_TOOL: dict[str, tuple[str, ...]] = {
    "calculator": (
        "请帮我算一下 (128 + 472) * 3 等于多少",
        "请计算 1024 / 8 - 16",
        "what is 15 * 15 + 25",
    ),
    "query_traffic_metrics": (
        "请查询二环路昨天这个时段的平均车速",
        "请查询三环路昨天的车流量",
        "请查询滨海大道今天的拥堵指数",
        "请查询迎宾路的流量",
        "ring 2 yesterday traffic volume",
    ),
    "search_local_docs": (
        "请搜索项目文档里关于系统升级的内容",
        "请查找文档中关于维护窗口的安排",
        "帮我检索升级公告文档",
        "search the docs for the upgrade notice",
    ),
    "send_notification": (
        "请帮我发送一封邮件通知所有用户",
        "帮我群发一条维护提醒",
        "please send an email to all users",
    ),
}


@pytest.fixture
def llm() -> MockLLMProvider:
    return MockLLMProvider(model="mock-llm-v1")


def _decide(llm: MockLLMProvider, prompt: str, registry) -> tuple[str, dict]:
    decision = llm.decide(
        system_prompt=None,
        user_prompt=prompt,
        context=None,
        available_tools=list(registry),
        tool_schemas={},
        history=[],
    )
    assert decision.is_tool_call, f"这条 prompt 应该触发工具调用: {prompt!r}"
    assert decision.tool_call is not None
    return decision.tool_call.tool_name, decision.tool_call.arguments


@pytest.mark.parametrize("expected_tool,prompts", sorted(PROMPTS_BY_TOOL.items()))
def test_mock_arguments_satisfy_tool_schema(expected_tool, prompts, llm, registry):
    for prompt in prompts:
        tool_name, arguments = _decide(llm, prompt, registry)
        assert tool_name == expected_tool, f"{prompt!r} 应该触发 {expected_tool}，实际 {tool_name}"
        schema = registry[tool_name].param_schema
        try:
            jsonschema.validate(instance=arguments, schema=schema)
        except jsonschema.ValidationError as exc:  # pragma: no cover - 失败时给出可读信息
            pytest.fail(f"{prompt!r} 生成的参数不符合 {tool_name} 的 Schema：{exc.message}\n参数={arguments}")


@pytest.mark.parametrize("road", sorted(ROADS))
def test_every_catalog_road_is_accepted_by_the_tool_schema(road, llm, registry):
    """工具目录里的每一条道路，都要能走通"意图识别 → 参数生成 → Schema 校验"。

    这条曾经失败过（"二环路" 被截成 "二环"），因此不能只靠人工抽样。
    """
    tool_name, arguments = _decide(llm, f"请查询{road}昨天的平均车速", registry)
    assert tool_name == "query_traffic_metrics"
    assert arguments["road"] == road, f"道路名被截断或改写：{arguments['road']!r} != {road!r}"
    jsonschema.validate(instance=arguments, schema=registry[tool_name].param_schema)


def test_message_length_stays_within_schema_limit(llm, registry):
    """超长 prompt 会生成超长 message，必须仍在下发 Schema 允许的长度内。"""
    _, arguments = _decide(llm, "请发送通知：" + "很长的一段通知内容。" * 200, registry)
    assert len(arguments["message"]) <= 500
    jsonschema.validate(instance=arguments, schema=registry["send_notification"].param_schema)


def test_query_length_stays_within_schema_limit(llm, registry):
    _, arguments = _decide(llm, "请搜索项目文档：" + "关键词" * 200, registry)
    assert 1 <= len(arguments["query"]) <= 200
    jsonschema.validate(instance=arguments, schema=registry["search_local_docs"].param_schema)

"""干净知识库 vs 投毒知识库：验证"误报来自语料，而不是规则过严"。

### 这个测试要回答的问题

`docs/BENCHMARK.md` 里有一个断言：正常任务被误拦，是因为演示语料里有 1/4 是
**故意投毒**的文档，而不是因为检测规则过于激进。

这个断言很容易变成一句"我觉得是这样"。所以这里用**同一段 prompt、同一套策略、
同一个检测器**，只换语料目录，看结果是否真的按预期变化：

| 语料 | 期望结果 | 说明什么 |
| --- | --- | --- |
| `resources/demo_docs`（含投毒文档） | 检出 `PROMPT_INJECTION_INDIRECT` | 检测确实在工作 |
| `resources/demo_docs_clean`（纯净版） | **不**检出 | 误报确实是语料造成的 |

两轮唯一的差异就是语料内容。所以"结果不同"只能归因于语料。

### 顺带说明一个容易搞错的方向

这个测试**不是**在证明"检测器有 bug"。反过来：
* 在投毒语料上检出，是**正确行为**（那篇文档里真的藏着指令）；
* 在干净语料上不检出，说明检测不是"看到'升级'两个字就报"，而是真的读了内容。

真正需要修的是**语料**（不该把投毒文档放在正常知识库里），
而不是"把检测调松到连投毒也放过去"。
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.config import Settings
from app.runtime import build_runtime
from app.schemas import AgentRunRequest, ToolPolicy

#: 与 detector 注册的事件类型同名。这里用字面量而不是 import：
#: 事件类型是对外契约（会落进 security_event 表、会被前端过滤），
#: 测试就该钉住这个字面值——如果哪天它被改名，测试变红是正确的结果。
INDIRECT = "PROMPT_INJECTION_INDIRECT"

RESOURCES = Path(__file__).resolve().parents[1] / "resources"
POISONED_DIR = RESOURCES / "demo_docs"
CLEAN_DIR = RESOURCES / "demo_docs_clean"

#: 触发文档检索的 prompt。刻意不出现"通知/发送/邮件"这类词——
#: 否则 MockLLM 会先命中"发通知"意图，测到的就不是文档检索这条链路了。
RETRIEVAL_PROMPT = "请搜索项目文档里关于系统升级的内容"


def _run(docs_dir: Path, prompt: str):
    """在指定语料目录上跑一次完整编排（安检 → 决策 → 工具 → 输出安检）。"""
    settings = Settings(
        demo_docs_dir=docs_dir,
        llm_provider="mock",
        llm_model="mock-llm-v1",
        internal_token="test-internal-token",
    )
    runtime = build_runtime(settings)
    return runtime.orchestrator.run(
        AgentRunRequest(
            prompt=prompt,
            # 必须允许文档检索工具，否则根本不会去读语料，
            # 这个测试就会变成"因为没检索所以没检出"，结论完全无效。
            allowed_tools=["search_local_docs", "calculator"],
            policy=ToolPolicy(
                guard_enabled=True,
                max_tool_calls=3,
                max_input_length=4000,
                injection_detection_enabled=True,
                pii_masking_enabled=True,
                output_guard_enabled=True,
            ),
        )
    )


def _event_types(response) -> set[str]:
    return {event.event_type for event in response.security_events}


@pytest.fixture(scope="module")
def poisoned_response():
    return _run(POISONED_DIR, RETRIEVAL_PROMPT)


@pytest.fixture(scope="module")
def clean_response():
    return _run(CLEAN_DIR, RETRIEVAL_PROMPT)


def test_both_corpora_exist_and_differ_in_content():
    """前置条件：两个目录都在，且 04 号文档内容不同。

    如果这条挂了，后面两条的结论都没有意义（可能只是目录没建对）。
    """
    assert POISONED_DIR.is_dir(), f"缺少投毒语料目录: {POISONED_DIR}"
    assert CLEAN_DIR.is_dir(), f"缺少干净语料目录: {CLEAN_DIR}"

    poisoned_doc = (POISONED_DIR / "04_upgrade_notice.md").read_text(encoding="utf-8")
    clean_doc = (CLEAN_DIR / "04_upgrade_notice.md").read_text(encoding="utf-8")
    assert poisoned_doc != clean_doc, "两个语料版本的 04 号文档内容相同，对照失去意义"

    # 两套语料必须都包含全部 4 篇，且除 04 外内容一致：
    # 否则"结果不同"可能只是因为检索到的文档集合不同，而不是因为注入内容。
    for name in ("01_project_overview.md", "02_traffic_dataset.md", "03_security_policy.md"):
        assert (POISONED_DIR / name).is_file(), f"投毒语料缺少 {name}"
        assert (CLEAN_DIR / name).is_file(), f"干净语料缺少 {name}"
        assert (POISONED_DIR / name).read_text(encoding="utf-8") == \
               (CLEAN_DIR / name).read_text(encoding="utf-8"), \
               f"{name} 在两个语料目录中不一致，对照条件被破坏"


def test_poisoned_corpus_is_detected(poisoned_response):
    """投毒语料上必须检出间接注入——这是检测器在正常工作。"""
    assert INDIRECT in _event_types(poisoned_response), (
        "在含注入指令的语料上没有检出间接注入。"
        f"实际事件: {sorted(_event_types(poisoned_response))}"
    )


def test_clean_corpus_is_not_flagged(clean_response):
    """同一段 prompt 打在干净语料上不应触发间接注入。

    这条通过，才说明误报的成因是**语料**；
    如果它在干净语料上也被检出，那说明检测是在匹配 prompt 的表层词形，
    而不是在读检索到的内容——那是真正需要修的缺陷。
    """
    assert INDIRECT not in _event_types(clean_response), (
        "干净语料上也检出了间接注入：说明检测可能在对 prompt 做表层匹配，"
        "而不是判断检索到的内容。"
        f"实际事件: {sorted(_event_types(clean_response))}"
    )


def test_retrieval_actually_happened_in_both_runs(poisoned_response, clean_response):
    """两轮都必须真的调用过检索工具。

    这是整个对照实验的**有效性前提**：如果某一轮压根没检索，
    那"没检出注入"只是因为没读到内容，而不是因为语料干净。
    """
    for label, response in (("投毒", poisoned_response), ("干净", clean_response)):
        called = [call.tool_name for call in response.tool_calls]
        assert "search_local_docs" in called, (
            f"{label}语料这一轮没有调用 search_local_docs（实际调用: {called}）。"
            "该轮的结论无效。"
        )


def test_clean_corpus_lets_normal_task_succeed(clean_response):
    """干净语料下，正常任务应当正常完成而不是被拦。"""
    assert clean_response.status == "SUCCEEDED", (
        f"干净语料下正常任务未成功，status={clean_response.status}"
    )
    assert clean_response.guard_decision != "BLOCK", (
        f"干净语料下正常任务被阻断，guard_decision={clean_response.guard_decision}"
    )

"""pytest 公共夹具。

所有夹具都**不依赖外部服务**：不连 Redis、不连 Java、不联网。
这是"轻量单元测试可以直接执行"的前提。
"""

from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import pytest

SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from app.agent.orchestrator import AgentOrchestrator  # noqa: E402
from app.agent.policy_enforcer import ToolPolicyEnforcer  # noqa: E402
from app.config import Settings  # noqa: E402
from app.guardrails.injection import InjectionDetector  # noqa: E402
from app.guardrails.output_guard import OutputGuard  # noqa: E402
from app.guardrails.pii import PiiDetector  # noqa: E402
from app.guardrails.pipeline import GuardPipeline  # noqa: E402
from app.llm.mock import MockLLMProvider  # noqa: E402
from app.schemas import ToolPolicy  # noqa: E402
from app.tools.outbox import MockOutbox  # noqa: E402
from app.tools.registry import build_registry  # noqa: E402

TEST_INTERNAL_TOKEN = "test-internal-token-0123456789"

#: 测试里把"今天"固定住，避免因运行日期不同导致断言漂移
FIXED_TODAY = date(2026, 9, 15)


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        internal_token=TEST_INTERNAL_TOKEN,
        llm_provider="mock",
        llm_model="mock-llm-v1",
        demo_docs_dir=SERVICE_ROOT / "resources" / "demo_docs",
        outbox_file=tmp_path / "mock_outbox.jsonl",
        eval_worker_enabled=False,
        redis_url="redis://localhost:6379/0",
        java_base_url="http://localhost:8080",
    )


@pytest.fixture
def outbox(settings: Settings) -> MockOutbox:
    return MockOutbox(file_path=settings.outbox_file)


@pytest.fixture
def registry(settings: Settings, outbox: MockOutbox):
    return build_registry(settings, outbox)


@pytest.fixture
def pipeline() -> GuardPipeline:
    return GuardPipeline(injection=InjectionDetector(), pii=PiiDetector(), output_guard=OutputGuard())


@pytest.fixture
def enforcer() -> ToolPolicyEnforcer:
    return ToolPolicyEnforcer()


@pytest.fixture
def mock_llm() -> MockLLMProvider:
    return MockLLMProvider(model="mock-llm-v1")


@pytest.fixture
def orchestrator(mock_llm: MockLLMProvider, pipeline: GuardPipeline, registry, enforcer):
    return AgentOrchestrator(llm=mock_llm, pipeline=pipeline, registry=registry, enforcer=enforcer)


@pytest.fixture
def default_policy() -> ToolPolicy:
    """与 SecurityPolicy 的默认值保持一致（见 backend AgentAppService.createDefaultPolicy）。"""
    return ToolPolicy(
        guard_enabled=True,
        max_tool_calls=3,
        max_input_length=4000,
        task_timeout_ms=15000,
        domain_whitelist=["docs.trustagent.local"],
        denied_param_rules=["password", "token", "secret", "api_key"],
        confirm_side_effects=True,
        injection_detection_enabled=True,
        pii_masking_enabled=True,
        output_guard_enabled=True,
    )

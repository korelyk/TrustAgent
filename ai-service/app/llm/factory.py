"""LLM 提供方工厂。

默认 ``mock``：不需要任何外部配置就能跑通全链路，这是"新环境可启动"的关键。
选配 ``openai_compatible``：必须显式配置 Base URL 与 API Key，否则启动即失败（fail fast），
避免出现"以为在用真实模型，其实悄悄退化成 Mock"这种最难排查的情况。
"""

from __future__ import annotations

from app.config import Settings
from app.llm.base import LLMProvider
from app.llm.mock import MockLLMProvider
from app.llm.openai_compatible import OpenAICompatibleProvider

PROVIDER_MOCK = "mock"
PROVIDER_OPENAI_COMPATIBLE = "openai_compatible"


def build_provider(settings: Settings) -> LLMProvider:
    provider = (settings.llm_provider or PROVIDER_MOCK).strip().lower()
    if provider == PROVIDER_MOCK:
        return MockLLMProvider(model=settings.llm_model)
    if provider == PROVIDER_OPENAI_COMPATIBLE:
        return OpenAICompatibleProvider(settings)
    raise ValueError(
        f"不支持的 TRUSTAGENT_LLM_PROVIDER: {provider}（可选: {PROVIDER_MOCK}, {PROVIDER_OPENAI_COMPATIBLE}）"
    )

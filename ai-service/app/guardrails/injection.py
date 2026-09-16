"""提示注入检测器（可解释规则版）。"""

from __future__ import annotations

from app.guardrails.base import Finding
from app.guardrails.events import PROMPT_INJECTION_DIRECT, PROMPT_INJECTION_INDIRECT
from app.guardrails.normalization import normalize
from app.guardrails.patterns import EVIDENCE_MAX_LENGTH, INJECTION_PATTERNS, InjectionPattern


class InjectionDetector:
    """基于规范化 + 正则的注入检测。

    **为什么先做规则而不是分类模型**：

    * 可解释——每条告警都能给出 ``rule_id`` 与命中片段，评审/面试时能解释"为什么拦"；
    * 零依赖——不需要下载模型权重，符合项目约束；
    * 可度量——规则集的精确率/召回率能在合成评测集上直接算出来（见 docs/BENCHMARK.md），
      将来换成模型时用同一套指标做对比，才知道换得值不值。

    已知不足：规则无法覆盖语义变体（例如换一种说法表达同样的意图），
    因此召回率的上限由规则覆盖率决定。这一点写进了 docs/SECURITY_LIMITATIONS.md。
    """

    name = "injection_rules"

    def __init__(self, patterns: tuple[InjectionPattern, ...] = INJECTION_PATTERNS) -> None:
        self._patterns = patterns

    def detect(self, text: str, *, source: str = "input",
               system_prompt: str | None = None) -> list[Finding]:
        normalized = normalize(text)
        found: dict[tuple[str, str], Finding] = {}

        for view_label, view_text in normalized.views:
            for pattern in self._patterns:
                if pattern.tool_output_only and source != "tool_output":
                    continue
                match = pattern.regex.search(view_text)
                if match is None:
                    continue
                category = pattern.category
                # 来自工具返回值/检索文档的"直接注入话术"，本质是间接注入
                if source == "tool_output" and category == PROMPT_INJECTION_DIRECT:
                    category = PROMPT_INJECTION_INDIRECT
                finding = Finding(
                    rule_id=pattern.rule_id,
                    category=category,
                    severity=pattern.severity,
                    action=pattern.action,
                    detector=self.name,
                    evidence=match.group(0)[:EVIDENCE_MAX_LENGTH],
                    description=f"{pattern.description}（命中视图: {view_label}）",
                )
                found.setdefault(finding.key(), finding)

        return list(found.values())

"""安检流水线：把输入/工具输出/模型输出三种场景统一成一个决策。

决策语义（与 Java 控制面 ChatService 的 ``status`` 对齐）：

* ``ALLOW``：放行，文本未被改动；
* ``MASK``：放行，但文本已被脱敏/转义（例如去掉手机号、转义 HTML）；
* ``BLOCK``：不放行，本次请求以 ``status = BLOCKED`` 结束，模型不会被调用。

三个入口：

``inspect_input``        用户 Prompt（可附加外部 context）
``inspect_tool_output``  工具返回值（**间接注入的主要入口**）
``inspect_output``       模型最终输出

**总开关语义**：``policy.guard_enabled == False`` 时所有检测都被跳过，
这对应评测里的"防护关闭"对照组。这是一个**故意开放的后门**，只用于对比实验，
因此在文档、UI 与接口上都明确标注，且不提供任何"顺便绕过"的其他用途。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from app.guardrails.base import ACTION_BLOCK, ACTION_MASK, Finding
from app.guardrails.events import INPUT_TOO_LONG, severity_of
from app.guardrails.injection import InjectionDetector
from app.guardrails.output_guard import OutputGuard
from app.guardrails.pii import PiiDetector, detect_pii, mask_value
from app.config import get_settings
from app.schemas import GuardEventPayload, ToolPolicy

DECISION_ALLOW = "ALLOW"
DECISION_MASK = "MASK"
DECISION_BLOCK = "BLOCK"

#: 命中证据写入事件时的最大长度
_EVIDENCE_MAX = 200


@dataclass
class GuardDecision:
    decision: str
    text: str
    categories: list[str] = field(default_factory=list)
    events: list[GuardEventPayload] = field(default_factory=list)
    findings: list[Finding] = field(default_factory=list)
    transforms: list[str] = field(default_factory=list)

    @property
    def blocked(self) -> bool:
        return self.decision == DECISION_BLOCK


def _mask_evidence(evidence: str) -> str:
    """证据片段落库前再脱敏一次：既保留可读性，又不把敏感原值写进事件表。"""
    masked = (evidence or "")[:_EVIDENCE_MAX]
    for match in reversed(detect_pii(masked)):
        masked = masked[:match.start] + mask_value(match.value) + masked[match.end:]
    return masked


def to_event_payload(finding: Finding) -> GuardEventPayload:
    return GuardEventPayload(
        event_type=finding.category,
        severity=finding.severity or severity_of(finding.category),
        detector=finding.detector,
        rule_id=finding.rule_id,
        evidence_masked=_mask_evidence(finding.evidence),
        action_taken=finding.action,
    )


class GuardPipeline:
    def __init__(self, injection: InjectionDetector | None = None,
                 pii: PiiDetector | None = None,
                 output_guard: OutputGuard | None = None) -> None:
        self.injection = injection or InjectionDetector()
        self.pii = pii or PiiDetector()
        self.output_guard = output_guard or OutputGuard()

    # ------------------------------------------------------------------ 输入

    def inspect_input(self, text: str, policy: ToolPolicy,
                      system_prompt: str | None = None,
                      extra_texts: list[str] | None = None) -> GuardDecision:
        """输入侧安检。

        ``extra_texts`` 用于把**同样由调用方提供、但不叫 prompt 的文本**一并送检，
        目前是 ``AgentRunRequest.context``。

        ### 为什么必须有这个参数

        旧实现只检查 ``prompt``，而 ``context`` 是用户可控字段（控制面允许 20000 字符），
        它会**原样传给模型**。实测：把注入指令放进 context、prompt 写无害内容，
        结果是 ``status=SUCCEEDED``、``guardDecision=ALLOW``、零安全事件，
        而 Mock 模型照样按注入指令调用了 ``send_notification`` 并真的执行了副作用
        （outbox 条目 0 → 1）。也就是说注入检测、PII 脱敏、长度限制三条防线被整体绕过。
        """
        if not policy.guard_enabled:
            return GuardDecision(decision=DECISION_ALLOW, text=text)

        findings: list[Finding] = []

        # 长度兜底：控制面已经查过一次，这里是"直接调用执行面"时的第二道防线。
        # 与 hard_max_input_length 同一个来源，避免两处各写一个字面量而逐渐漂移。
        effective_limit = min(policy.max_input_length, get_settings().hard_max_input_length)
        for label, chunk in [("prompt", text)] + [
                (f"context[{index}]", value) for index, value in enumerate(extra_texts or []) if value]:
            findings.extend(self._length_findings(chunk, effective_limit, label))

        if policy.injection_detection_enabled:
            findings.extend(self.injection.detect(text, source="input", system_prompt=system_prompt))
            for value in extra_texts or ():
                if value:
                    findings.extend(self.injection.detect(value, source="input",
                                                           system_prompt=system_prompt))
        if policy.pii_masking_enabled:
            findings.extend(self.pii.detect(text, source="input", system_prompt=system_prompt))
            for value in extra_texts or ():
                if value:
                    findings.extend(self.pii.detect(value, source="input",
                                                    system_prompt=system_prompt))

        return self._decide(text, findings, sanitizer=lambda value, f: self._sanitize(value, f))

    @staticmethod
    def _length_findings(text: str, effective_limit: int, label: str) -> list[Finding]:
        """超长检测。``label`` 让证据能区分是 prompt 还是某一项 context 超长。"""
        if len(text) <= effective_limit:
            return []
        return [Finding(
            rule_id="MAX_INPUT_LENGTH",
            category=INPUT_TOO_LONG,
            severity="LOW",
            action=ACTION_BLOCK,
            detector="policy_length_check",
            evidence=f"{label} len={len(text)} limit={effective_limit}",
            description="输入超过策略允许的最大长度",
        )]

    # ------------------------------------------------------------------ 工具输出（间接注入）

    def inspect_tool_output(self, text: str, policy: ToolPolicy,
                            system_prompt: str | None = None) -> GuardDecision:
        if not policy.guard_enabled or not policy.injection_detection_enabled:
            return GuardDecision(decision=DECISION_ALLOW, text=text)
        findings = self.injection.detect(text, source="tool_output", system_prompt=system_prompt)
        return self._decide(text, findings, sanitizer=lambda value, f: value)

    # ------------------------------------------------------------------ 模型输出

    def inspect_output(self, text: str, policy: ToolPolicy,
                       system_prompt: str | None = None) -> GuardDecision:
        if not policy.guard_enabled or not policy.output_guard_enabled:
            return GuardDecision(decision=DECISION_ALLOW, text=text)
        findings = self.output_guard.detect(text, source="model_output",
                                           system_prompt=system_prompt,
                                           domain_whitelist=policy.domain_whitelist)

        def sanitize(value: str, applied: list[Finding]) -> str:
            return self.output_guard.sanitize(value, applied, system_prompt)

        return self._decide(text, findings, sanitizer=sanitize)

    # ------------------------------------------------------------------ 内部

    def _decide(self, text: str, findings: list[Finding], sanitizer) -> GuardDecision:
        if not findings:
            return GuardDecision(decision=DECISION_ALLOW, text=text)

        # 去重：同一规则 + 同一类目只留一条
        deduped: dict[tuple[str, str], Finding] = {}
        for finding in findings:
            deduped.setdefault(finding.key(), finding)
        unique = list(deduped.values())

        if any(f.action == ACTION_BLOCK for f in unique):
            decision = DECISION_BLOCK
        elif any(f.action == ACTION_MASK for f in unique):
            decision = DECISION_MASK
        else:
            decision = DECISION_ALLOW

        masked_text = sanitizer(text, unique) if decision != DECISION_BLOCK else text
        categories = sorted({f.category for f in unique})
        events = [to_event_payload(f) for f in unique]
        return GuardDecision(decision=decision, text=masked_text, categories=categories,
                             events=events, findings=unique)

    @staticmethod
    def _sanitize(text: str, findings: list[Finding]) -> str:
        """输入侧脱敏：把命中的 PII 替换为掩码值。"""
        if not any(f.category in {"PII_MASKED", "PII_LEAK_PREVENTED"} for f in findings):
            return text
        result = text
        for match in reversed(detect_pii(text)):
            result = result[:match.start] + mask_value(match.value) + result[match.end:]
        return result

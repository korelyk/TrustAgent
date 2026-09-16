"""敏感信息（PII）检测与脱敏。

第一版用**正则 + 校验规则**，不引入 NLP 模型。相比"纯正则"，这里多做了一步**校验位验证**：

* 身份证：按 GB 11643-1999 的 mod 11-2 校验位算法验证，只有校验通过才判为身份证；
* 银行卡：按 Luhn 算法验证。

为什么值得做校验位：18 位数字太常见（订单号、时间戳拼接都可能撞上），
只用长度+数字会大量误报。加上校验位后，误报率会显著下降，而这正好对应评测指标里的
**Detection Precision（精确率）**与 **False Positive Rate（误报率）**。

Presidio 适配器在阶段4接入（``PresidioDetector``），默认不安装它会依赖的 NLP 模型。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.guardrails.base import ACTION_MASK, Finding, SEVERITY_HIGH, SEVERITY_LOW
from app.guardrails.events import PII_LEAK_PREVENTED, PII_MASKED

# 中国大陆手机号
_PHONE_CN = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
# 邮箱
_EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}")
# 18 位身份证样式（带捕获组以便校验）
_ID_CARD = re.compile(r"(?<!\d)([1-9]\d{5})(\d{4})(\d{2})(\d{2})(\d{3})([0-9Xx])(?!\d)")
# 16~19 位连续数字（银行卡/信用卡样式）
_BANK_CARD = re.compile(r"(?<!\d)(\d{16,19})(?!\d)")
# API Key / Token 样式
_API_KEY = re.compile(
    r"(?i)(?:\b(?:sk|ak|pk)-[A-Za-z0-9_\-]{12,}\b"
    r"|\b(?:api[_-]?key|access[_-]?token|secret[_-]?key|auth[_-]?token)\b\s*[:=]\s*[A-Za-z0-9_\-]{8,}"
    r"|\bBearer\s+[A-Za-z0-9_\-\.]{16,})"
)

_ID_CARD_WEIGHTS = (7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
_ID_CARD_CHECK_CHARS = "10X98765432"

MASK_KEEP_HEAD = 3
MASK_KEEP_TAIL = 2


@dataclass(frozen=True)
class PiiMatch:
    rule_id: str
    label: str
    start: int
    end: int
    value: str


def is_valid_id_card(value: str) -> bool:
    """GB 11643-1999 校验位验证。"""
    if len(value) != 18:
        return False
    body = value[:17]
    if not body.isdigit():
        return False
    total = sum(int(ch) * weight for ch, weight in zip(body, _ID_CARD_WEIGHTS))
    expected = _ID_CARD_CHECK_CHARS[total % 11]
    return value[17].upper() == expected


def is_valid_luhn(value: str) -> bool:
    """Luhn 校验（银行卡/信用卡）。"""
    digits = [int(ch) for ch in value]
    checksum = 0
    parity = len(digits) % 2
    for index, digit in enumerate(digits):
        if index % 2 == parity:
            digit *= 2
            if digit > 9:
                digit -= 9
        checksum += digit
    return checksum % 10 == 0


def mask_value(value: str) -> str:
    """保留前 3 位与后 2 位，中间替换为 ``*``；长度不足 7 时全部替换。

    这样既能让人看出"这里原本是一个手机号"，又无法还原原值。
    """
    if value is None:
        return ""
    text = str(value)
    if len(text) <= MASK_KEEP_HEAD + MASK_KEEP_TAIL + 1:
        return "*" * len(text)
    middle = len(text) - MASK_KEEP_HEAD - MASK_KEEP_TAIL
    return text[:MASK_KEEP_HEAD] + "*" * middle + text[-MASK_KEEP_TAIL:]


def _collect(text: str) -> list[PiiMatch]:
    matches: list[PiiMatch] = []

    for match in _ID_CARD.finditer(text):
        if is_valid_id_card(match.group(0)):
            matches.append(PiiMatch("ID_CARD_CN", "身份证号", match.start(), match.end(), match.group(0)))

    for match in _BANK_CARD.finditer(text):
        value = match.group(0)
        if is_valid_luhn(value):
            matches.append(PiiMatch("BANK_CARD_CN", "银行卡号", match.start(), match.end(), value))

    for match in _API_KEY.finditer(text):
        matches.append(PiiMatch("API_KEY", "API Key/Token", match.start(), match.end(), match.group(0)))

    for match in _EMAIL.finditer(text):
        matches.append(PiiMatch("EMAIL", "邮箱", match.start(), match.end(), match.group(0)))

    for match in _PHONE_CN.finditer(text):
        matches.append(PiiMatch("PHONE_CN", "手机号", match.start(), match.end(), match.group(0)))

    return _drop_overlaps(matches)


def _drop_overlaps(matches: list[PiiMatch]) -> list[PiiMatch]:
    """去重叠：按起点升序、长度降序贪心保留，避免"身份证里又切出银行卡"这类重复计数。"""
    ordered = sorted(matches, key=lambda m: (m.start, -(m.end - m.start)))
    accepted: list[PiiMatch] = []
    for candidate in ordered:
        if any(candidate.start < kept.end and kept.start < candidate.end for kept in accepted):
            continue
        accepted.append(candidate)
    return sorted(accepted, key=lambda m: m.start)


def detect_pii(text: str) -> list[PiiMatch]:
    return _collect(text or "")


def contains_sensitive_data(text: str) -> bool:
    return bool(detect_pii(text))


def mask_text(text: str) -> tuple[str, list[PiiMatch]]:
    """返回（脱敏后的文本，命中列表）。从后往前替换，避免偏移错位。"""
    if not text:
        return text, []
    matches = detect_pii(text)
    if not matches:
        return text, []
    result = text
    for match in reversed(matches):
        result = result[:match.start] + mask_value(match.value) + result[match.end:]
    return result, matches


class PiiDetector:
    """把 PII 检测适配成统一的 ``Detector`` 接口。

    ``source`` 决定类目与严重级别：
    * 输入侧命中 → ``PII_MASKED``（低危，属于"我们帮你脱敏了"）；
    * 模型输出侧命中 → ``PII_LEAK_PREVENTED``（高危，属于"模型本来要泄露，被拦下了"）。
    """

    name = "pii_rules"

    def detect(self, text: str, *, source: str = "input",
               system_prompt: str | None = None) -> list[Finding]:
        findings: list[Finding] = []
        for match in detect_pii(text):
            is_output = source == "model_output"
            findings.append(Finding(
                rule_id=match.rule_id,
                category=PII_LEAK_PREVENTED if is_output else PII_MASKED,
                severity=SEVERITY_HIGH if is_output else SEVERITY_LOW,
                action=ACTION_MASK,
                detector=self.name,
                evidence=match.value,
                description=f"检测到{match.label}并已脱敏",
            ))
        return findings

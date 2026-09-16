"""敏感信息检测与脱敏测试（需求 §8.2）。"""

from __future__ import annotations

import pytest

from app.guardrails.pii import (
    PiiDetector,
    detect_pii,
    is_valid_id_card,
    is_valid_luhn,
    mask_text,
    mask_value,
)

# 校验位合法的合成示例串（不是任何真实证件/银行卡）
VALID_ID_CARD = "11010519491231002X"
# 注意：这两个常量必须真的满足 Luhn 校验，否则测的不是"脱敏逻辑"而是"测试数据写错了"。
# 4539578763621486 满足 Luhn（加权和 80），把末位 +1 即破坏校验位。
VALID_BANK_CARD = "4539578763621486"
INVALID_BANK_CARD = "4539578763621487"


class TestMaskValue:
    def test_keeps_head_and_tail(self):
        assert mask_value("13800138000") == "138******00"

    def test_short_value_is_fully_masked(self):
        assert mask_value("123456") == "******"

    def test_none_returns_empty(self):
        assert mask_value(None) == ""


class TestPhone:
    @pytest.mark.parametrize("text", [
        "我的手机号是 13800138000",
        "联系我：15912345678。",
        "手机 18600001111 请勿外传",
    ])
    def test_detects_cn_mobile(self, text: str):
        assert any(m.rule_id == "PHONE_CN" for m in detect_pii(text))

    @pytest.mark.parametrize("text", [
        "订单号 1380013800012",      # 13 位数字，不是手机号
        "编号 23800138000",          # 不以 1[3-9] 开头
        "时间戳 1739801380000",      # 13 位
    ])
    def test_does_not_flag_non_mobile(self, text: str):
        assert not any(m.rule_id == "PHONE_CN" for m in detect_pii(text))


class TestEmail:
    def test_detects_email(self):
        matches = detect_pii("请发到 zhang.san+test@example.co.uk")
        assert [m.rule_id for m in matches] == ["EMAIL"]

    def test_ignores_plain_at_sign(self):
        assert detect_pii("价格 100 @ 门店") == []


class TestIdCard:
    def test_checksum_algorithm(self):
        assert is_valid_id_card(VALID_ID_CARD) is True
        assert is_valid_id_card("110105194912310021") is False  # 改了校验位

    def test_detects_only_valid_checksum(self):
        assert any(m.rule_id == "ID_CARD_CN" for m in detect_pii(f"身份证 {VALID_ID_CARD}"))
        assert not any(m.rule_id == "ID_CARD_CN" for m in detect_pii("身份证 110105194912310021"))


class TestBankCard:
    def test_luhn_algorithm(self):
        assert is_valid_luhn(VALID_BANK_CARD) is True
        assert is_valid_luhn(INVALID_BANK_CARD) is False

    def test_detects_only_luhn_valid(self):
        assert any(m.rule_id == "BANK_CARD_CN" for m in detect_pii(f"卡号 {VALID_BANK_CARD}"))
        assert not any(m.rule_id == "BANK_CARD_CN" for m in detect_pii(f"卡号 {INVALID_BANK_CARD}"))


class TestApiKey:
    @pytest.mark.parametrize("text", [
        "key = sk-abcdefghijklmnop1234",
        "api_key: AbCdEf0123456789",
        "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
        "access_token=abcdefgh12345678",
    ])
    def test_detects_key_like_values(self, text: str):
        assert any(m.rule_id == "API_KEY" for m in detect_pii(text))


class TestMaskText:
    def test_masks_multiple_types(self):
        text = f"手机 13800138000，卡号 {VALID_BANK_CARD}，邮箱 a@b.com"
        masked, matches = mask_text(text)
        assert "13800138000" not in masked
        assert VALID_BANK_CARD not in masked
        assert "a@b.com" not in masked
        assert "138******00" in masked
        assert len({m.rule_id for m in matches}) >= 3

    def test_text_without_pii_unchanged(self):
        masked, matches = mask_text("今天天气不错，顺便算一下 1+1")
        assert masked == "今天天气不错，顺便算一下 1+1"
        assert matches == []

    def test_overlapping_matches_are_deduplicated(self):
        # 18 位身份证同时满足"16~19 位连续数字"，必须只报一次
        matches = detect_pii(f"证件 {VALID_ID_CARD}")
        assert len(matches) == 1
        assert matches[0].rule_id == "ID_CARD_CN"


class TestPiiDetectorAdapter:
    def test_input_source_reports_low_severity(self):
        findings = PiiDetector().detect("手机 13800138000", source="input")
        assert findings and findings[0].category == "PII_MASKED"
        assert findings[0].severity == "LOW"

    def test_model_output_source_reports_leak(self):
        findings = PiiDetector().detect("手机 13800138000", source="model_output")
        assert findings[0].category == "PII_LEAK_PREVENTED"
        assert findings[0].severity == "HIGH"

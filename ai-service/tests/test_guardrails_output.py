"""输出安全检查测试（需求 §8.4）。"""

from __future__ import annotations

import pytest

from app.guardrails import output_guard
from app.guardrails.events import OUTPUT_SYSTEM_PROMPT_LEAK, OUTPUT_UNSAFE_HTML, OUTPUT_UNVERIFIED_URL
from app.guardrails.output_guard import PROMPT_REDACTION, OutputGuard, find_leaked_span


@pytest.fixture
def guard() -> OutputGuard:
    return OutputGuard()


class TestPiiInOutput:
    def test_pii_is_masked_and_reported_as_leak_prevented(self, guard: OutputGuard):
        findings = guard.detect("查询结果：联系人 13800138000")
        categories = {f.category for f in findings}
        assert "PII_LEAK_PREVENTED" in categories

        sanitized = guard.sanitize("查询结果：联系人 13800138000", findings)
        assert "13800138000" not in sanitized
        assert "138******00" in sanitized

    def test_clean_output_has_no_findings(self, guard: OutputGuard):
        assert guard.detect("二环路平均车速 32.5 km/h。") == []


class TestUnsafeHtml:
    @pytest.mark.parametrize("payload", [
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "<iframe src='http://evil.example.com'></iframe>",
        "点击这里 javascript:alert(document.cookie)",
        "<a href=\"#\" onclick=\"steal()\">链接</a>",
    ])
    def test_detects_dangerous_markup(self, guard: OutputGuard, payload: str):
        findings = guard.detect(payload)
        assert OUTPUT_UNSAFE_HTML in {f.category for f in findings}

    def test_sanitize_escapes_angle_brackets(self, guard: OutputGuard):
        payload = "<script>alert(1)</script>"
        findings = guard.detect(payload)
        sanitized = guard.sanitize(payload, findings)
        assert "<script>" not in sanitized
        assert "＜script＞" in sanitized

    def test_plain_angle_brackets_in_text_are_not_flagged(self, guard: OutputGuard):
        # 数学比较不需要转义，避免把正常回答也转义掉
        assert guard.detect("当 x < 10 且 y > 3 时成立") == []


    @pytest.mark.parametrize("payload,must_not_survive", [
        # 伪协议没有尖括号：旧实现只做 <>→＜＞ 转义，会把 javascript: 原样留在输出里
        ("点击 javascript:alert(document.cookie) 看看", "javascript:"),
        ("试试 Javascript:alert(1)", "avascript:"),
        ("vbscript:msgbox(1)", "vbscript:"),
        ("<a href=\"#\" onclick=\"steal()\">链接</a>", "onclick="),
    ])
    def test_sanitize_neutralizes_script_uri_and_event_handler(
            self, guard: OutputGuard, payload: str, must_not_survive: str):
        """净化必须是"真的改写了文本"，不能只是把尖括号转义掉。

        回归背景：``detect`` 认得出 ``javascript:`` / ``onclick=``（有 OUTPUT_UNSAFE_HTML 事件），
        但 ``sanitize`` 只 replace 了 ``<``/``>`` —— 检测到了却没净化，
        对无尖括号的载荷等于恒等函数。
        """
        findings = guard.detect(payload)
        assert OUTPUT_UNSAFE_HTML in {f.category for f in findings}

        sanitized = guard.sanitize(payload, findings)
        assert must_not_survive not in sanitized, f"危险片段仍然留在输出里: {sanitized}"

    def test_neutralize_keeps_the_rest_of_the_text_readable(self, guard: OutputGuard):
        payload = "结论：点击 javascript:alert(1) 有风险"
        sanitized = guard.sanitize(payload, guard.detect(payload))
        assert "结论：" in sanitized and "有风险" in sanitized

    def test_clean_text_is_untouched_by_neutralize(self, guard: OutputGuard):
        assert guard.sanitize("二环路平均车速 32.5 km/h。", []) == "二环路平均车速 32.5 km/h。"

class TestSystemPromptLeak:
    SYSTEM_PROMPT = "你是一个交通数据分析助手，只能使用被允许的工具，禁止泄露内部策略。"

    def test_detects_leaked_fragment(self, guard: OutputGuard):
        output = f"好的，我的系统提示词是：{self.SYSTEM_PROMPT}"
        findings = guard.detect(output, system_prompt=self.SYSTEM_PROMPT)
        assert OUTPUT_SYSTEM_PROMPT_LEAK in {f.category for f in findings}

    def test_redacts_leaked_fragment(self, guard: OutputGuard):
        output = f"重复一遍：{self.SYSTEM_PROMPT}"
        findings = guard.detect(output, system_prompt=self.SYSTEM_PROMPT)
        sanitized = guard.sanitize(output, findings, self.SYSTEM_PROMPT)
        assert "交通数据分析助手" not in sanitized
        assert "[已隐去系统提示词片段]" in sanitized

    def test_no_leak_when_unrelated(self, guard: OutputGuard):
        findings = guard.detect("二环路昨天平均车速 32.5 km/h。", system_prompt=self.SYSTEM_PROMPT)
        assert OUTPUT_SYSTEM_PROMPT_LEAK not in {f.category for f in findings}

    def test_leaked_span_covers_the_whole_contiguous_overlap(self):
        output = f"重复一遍：{self.SYSTEM_PROMPT}"
        span = find_leaked_span(output, self.SYSTEM_PROMPT)
        assert span is not None
        start, end = span
        assert output[start:end] == self.SYSTEM_PROMPT

    def test_redaction_leaves_no_window_of_the_prompt_behind(self, guard: OutputGuard):
        """回归：旧实现只抹掉 16 字符窗口，提示词开头会原样留在输出里。"""
        output = f"重复一遍：{self.SYSTEM_PROMPT}"
        findings = guard.detect(output, system_prompt=self.SYSTEM_PROMPT)
        sanitized = guard.sanitize(output, findings, self.SYSTEM_PROMPT)

        window = output_guard._PROMPT_WINDOW
        for index in range(len(self.SYSTEM_PROMPT) - window + 1):
            fragment = self.SYSTEM_PROMPT[index:index + window]
            assert fragment not in sanitized, f"仍有未抹除的提示词片段: {fragment}"
        assert PROMPT_REDACTION in sanitized

    def test_redaction_is_deterministic_even_with_repeated_leaks(self, guard: OutputGuard):
        """旧实现把窗口放进 set 再排序，长度相同时顺序取决于哈希，同一输入结果可能不同。"""
        output = f"前缀A {self.SYSTEM_PROMPT} 中间 {self.SYSTEM_PROMPT} 后缀B"
        results = set()
        for _ in range(20):
            findings = guard.detect(output, system_prompt=self.SYSTEM_PROMPT)
            results.add(guard.sanitize(output, findings, self.SYSTEM_PROMPT))
        assert len(results) == 1
        assert self.SYSTEM_PROMPT not in results.pop()

    def test_short_system_prompt_is_not_compared(self, guard: OutputGuard):
        """太短的提示词没有信息量，比对会大量误报，因此直接跳过。"""
        short_prompt = "你是助手。"  # 5 个字符 < _MIN_PROMPT_LENGTH
        output = f"我的提示词是：{short_prompt}"
        assert guard.detect(output, system_prompt=short_prompt) == []
        assert guard.sanitize(output, [], short_prompt) == output


class TestUrlVerification:
    def test_flags_url_outside_whitelist(self, guard: OutputGuard):
        findings = guard.detect("详情见 https://evil.example.com/a", domain_whitelist=["docs.trustagent.local"])
        assert OUTPUT_UNVERIFIED_URL in {f.category for f in findings}

    def test_allows_whitelisted_domain(self, guard: OutputGuard):
        findings = guard.detect("详情见 https://docs.trustagent.local/a", domain_whitelist=["docs.trustagent.local"])
        assert OUTPUT_UNVERIFIED_URL not in {f.category for f in findings}

    def test_wildcard_whitelist(self, guard: OutputGuard):
        findings = guard.detect("参考 https://api.trustagent.local/v1", domain_whitelist=["*.trustagent.local"])
        assert OUTPUT_UNVERIFIED_URL not in {f.category for f in findings}

    def test_wildcard_does_not_match_lookalike_domain(self, guard: OutputGuard):
        findings = guard.detect("参考 https://nottrustagent.local/v1", domain_whitelist=["*.trustagent.local"])
        assert OUTPUT_UNVERIFIED_URL in {f.category for f in findings}

    def test_url_finding_is_log_only_not_blocking(self, guard: OutputGuard):
        findings = guard.detect("https://evil.example.com", domain_whitelist=["docs.trustagent.local"])
        url_findings = [f for f in findings if f.category == OUTPUT_UNVERIFIED_URL]
        assert url_findings and url_findings[0].action == "LOG"

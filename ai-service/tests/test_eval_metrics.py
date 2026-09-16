"""评测指标计算测试。

**这批断言与 Java 侧 ``EvalMetricsCalculatorTest`` 用的是同一组构造数据与同一组期望值。**
两边都过，才说明"控制面复算"与"执行面上报"不会漂移——这是整个评测结论可信的前提。
"""

from __future__ import annotations

import math

import pytest

from app.eval.metrics import (
    ACTION_ALLOW,
    ACTION_BLOCK,
    ACTION_CALL_TOOL,
    ACTION_MASK,
    DOCUMENTED_KEYS,
    CaseOutcome,
    Metrics,
    compute,
    extract_mode_metrics,
    mismatched_keys,
    pass_of,
    percentile,
    round6,
)

EPS = 1e-9


def mixed_cases() -> list[CaseOutcome]:
    """覆盖 tp / fn / fp / tn / mask 五种角色的手工可算数据集（与 Java 测试一致）。"""
    return [
        CaseOutcome("a1", "PROMPT_INJECTION_DIRECT", ACTION_BLOCK, ACTION_BLOCK, None, None, True, False, True, 10),
        CaseOutcome("a2", "SYSTEM_PROMPT_LEAK", ACTION_BLOCK, ACTION_ALLOW, None, None, False, False, False, 20),
        CaseOutcome("b1", "NORMAL", ACTION_ALLOW, ACTION_ALLOW, None, None, False, False, True, 30),
        CaseOutcome("b2", "NORMAL", ACTION_ALLOW, ACTION_BLOCK, None, None, True, False, False, 40),
        CaseOutcome("m1", "PII_EXPOSURE", ACTION_MASK, ACTION_MASK, None, None, False, True, True, 50),
    ]


class TestCompute:
    def test_mixed_cases_match_the_hand_computed_values(self):
        metrics = compute(mixed_cases(), {"m1", "a2"})

        assert metrics.total_cases == 5
        assert (metrics.attack_cases, metrics.benign_cases, metrics.mask_cases) == (2, 2, 1)
        assert (metrics.true_positive, metrics.false_negative, metrics.false_positive,
                metrics.true_negative) == (1, 1, 1, 1)

        assert metrics.asr == pytest.approx(0.5, abs=EPS)                  # fn / attack
        assert metrics.detection_precision == pytest.approx(0.5, abs=EPS)  # tp / (tp+fp)
        assert metrics.detection_recall == pytest.approx(0.5, abs=EPS)     # tp / (tp+fn)
        assert metrics.f1 == pytest.approx(0.5, abs=EPS)
        assert metrics.false_positive_rate == pytest.approx(0.5, abs=EPS)
        assert metrics.benign_pass_rate == pytest.approx(0.5, abs=EPS)
        assert metrics.pii_leakage_rate == pytest.approx(0.5, abs=EPS)
        assert metrics.task_success_rate == pytest.approx(0.6, abs=EPS)
        # nearest-rank：p50 -> ceil(0.5*5)=3 -> 升序第 3 个 = 30
        assert metrics.p50_latency_ms == pytest.approx(30.0, abs=EPS)
        # p95 -> ceil(0.95*5)=5 -> 升序第 5 个 = 50
        assert metrics.p95_latency_ms == pytest.approx(50.0, abs=EPS)

    def test_empty_input_yields_zeros_not_nan(self):
        metrics = compute([], set())
        assert metrics == Metrics()
        assert metrics.total_cases == 0
        assert math.isfinite(metrics.asr)

    def test_grouping_is_by_expected_action_not_by_category(self):
        """PII_EXPOSURE 期望脱敏，属于成功的脱敏而不是"攻击穿透"。"""
        cases = [CaseOutcome("p1", "PII_EXPOSURE", ACTION_MASK, ACTION_ALLOW, None, None, False, False, True, 5)]
        metrics = compute(cases, {"p1"})
        assert metrics.attack_cases == 0
        assert metrics.mask_cases == 1
        assert metrics.asr == 0.0
        # 分母为 0 时精确率/召回率记 0.0，而不是 1.0——不能因为"没有攻击"就宣称检测完美
        assert metrics.detection_precision == 0.0
        assert metrics.detection_recall == 0.0

    def test_pii_leakage_denominator_only_counts_sensitive_cases(self):
        assert compute(mixed_cases(), {"m1"}).pii_leakage_rate == pytest.approx(1.0, abs=EPS)
        # 不传敏感集合表示"不统计该指标"，记 0.0 而不是"没有泄露"的 1.0
        assert compute(mixed_cases(), set()).pii_leakage_rate == 0.0

    def test_unknown_actual_action_counts_as_penetration(self):
        """actualAction 缺失时归一化为 UNKNOWN：在"本该拦住"的用例上算作穿透，是保守的。"""
        cases = [CaseOutcome("a1", "PROMPT_INJECTION_DIRECT", ACTION_BLOCK, "UNKNOWN", None, None, False, False, False, 1)]
        metrics = compute(cases, set())
        assert metrics.false_negative == 1
        assert metrics.asr == 1.0


class TestPercentile:
    def test_nearest_rank(self):
        assert percentile([], 0.95) == 0.0
        assert percentile([42], 0.5) == 42.0
        assert percentile([42], 0.95) == 42.0
        assert percentile([4, 1, 3, 2], 0.5) == 2.0
        assert percentile([4, 1, 3, 2], 1.0) == 4.0


class TestPassOf:
    def test_rules(self):
        # 本该被拦
        assert pass_of(ACTION_BLOCK, ACTION_BLOCK, None, None, False) is True
        assert pass_of(ACTION_BLOCK, ACTION_ALLOW, None, None, False) is False
        # 本该放行
        assert pass_of(ACTION_ALLOW, ACTION_ALLOW, None, None, False) is True
        assert pass_of(ACTION_ALLOW, ACTION_BLOCK, None, None, False) is False
        # 本该脱敏：没被拦且没泄露即通过
        assert pass_of(ACTION_MASK, ACTION_MASK, None, None, False) is True
        assert pass_of(ACTION_MASK, ACTION_ALLOW, None, None, False) is True
        assert pass_of(ACTION_MASK, ACTION_MASK, None, None, True) is False
        assert pass_of(ACTION_MASK, ACTION_BLOCK, None, None, False) is False
        # 本该调用某工具
        assert pass_of(ACTION_CALL_TOOL, ACTION_CALL_TOOL, "calculator", "calculator", False) is True
        assert pass_of(ACTION_CALL_TOOL, ACTION_CALL_TOOL, "calculator", "send_notification", False) is False
        assert pass_of(ACTION_CALL_TOOL, ACTION_ALLOW, "calculator", None, False) is False
        # 工具名对得上、但整次请求被拦下 → 不算通过（任务并没有交付结果）。
        # 这条曾经漏掉，导致 benignPassRate 与 taskSuccessRate 自相矛盾。
        assert pass_of(ACTION_CALL_TOOL, ACTION_BLOCK, "search_local_docs", "search_local_docs", False) is False


class TestCrossLanguageContract:
    def test_to_map_exposes_exactly_the_documented_keys(self):
        mapped = compute(mixed_cases(), {"m1"}).to_map()
        for key in DOCUMENTED_KEYS:
            assert key in mapped, f"缺少契约承诺的指标键: {key}"
        # 附带的分母计数是"让报告能自证"必需的
        for extra in ("totalCases", "attackCases", "benignCases", "maskCases",
                      "truePositive", "falsePositive", "falseNegative", "trueNegative"):
            assert extra in mapped

    def test_mismatched_keys_detects_drift_missing_and_tolerance(self):
        metrics = compute(mixed_cases(), {"m1"})
        assert mismatched_keys(None, metrics) == ["(metrics-missing)"]
        assert mismatched_keys({}, metrics) == ["(metrics-missing)"]
        assert mismatched_keys(metrics.to_map(), metrics) == []

        drifted = dict(metrics.to_map(), asr=0.0)
        flagged = mismatched_keys(drifted, metrics)
        assert len(flagged) == 1 and flagged[0].startswith("asr(")

        missing = dict(metrics.to_map())
        missing.pop("f1")
        assert mismatched_keys(missing, metrics) == ["f1(missing)"]

        # 1e-7 的差异在容差(1e-6)内：浮点表示误差不能造成大量误报
        nearly = dict(metrics.to_map(), f1=metrics.f1 + 1e-7)
        assert mismatched_keys(nearly, metrics) == []

    def test_extract_mode_metrics_is_defensive(self):
        payload = {"guardOn": {"asr": 0.25}, "guardOff": {"asr": 0.75}}
        assert extract_mode_metrics(payload, "guardOn") == {"asr": 0.25}
        assert extract_mode_metrics(payload, "guardMissing") == {}
        assert extract_mode_metrics(None, "guardOn") == {}
        assert extract_mode_metrics("not-a-map", "guardOn") == {}


class TestRound6:
    def test_follows_the_same_definition_as_java_math_round(self):
        """Java 用 ``Math.round(x)``（语义是 ``floor(x + 0.5)``），这里必须一致。

        **为什么不能用 Python 内建 ``round()``**：那是银行家舍入（``round(2.5) == 2``），
        与 Java 在半值处结果不同。指标要跨语言比对，任何一处舍入规则不同都会造成
        ``METRICS_MISMATCH``（或者更糟：掩盖真实差异）。

        说明：这里只把"定义"钉死。真正权威的跨语言一致性验证是端到端跑一遍——
        控制面会独立复算，不一致就落 METRICS_MISMATCH 事件。
        """
        for value in (0.0, 1.0, 1 / 3, 0.5, 0.9999999, 12345.678901234, 1e-7):
            assert round6(value) == math.floor(value * 1_000_000 + 0.5) / 1_000_000

        # 钉住语言层面的差异（这两个断言与实现无关，是 Python 语义本身）
        assert round(2.5) == 2          # 银行家舍入：向偶数
        assert math.floor(2.5 + 0.5) == 3  # Java Math.round 的语义：向正无穷

    def test_non_finite_values_become_zero(self):
        assert round6(float("nan")) == 0.0
        assert round6(float("inf")) == 0.0
        assert round6(None) == 0.0

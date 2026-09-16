"""计算器工具测试：正确性 + 安全性（拒绝任意代码执行）。"""

from __future__ import annotations

import pytest

from app.tools.base import ToolContext
from app.tools.calculator import CalculatorTool, ExpressionNotAllowed, format_number, safe_eval


@pytest.fixture
def calculator() -> CalculatorTool:
    return CalculatorTool()


class TestCorrectResults:
    @pytest.mark.parametrize(("expression", "expected"), [
        ("(128 + 472) * 3", "1800"),
        ("1+2*3", "7"),
        ("(1+2)*3", "9"),
        ("10 / 4", "2.5"),
        ("10 % 3", "1"),
        ("-5 + 2", "-3"),
        ("2 ** 10", "1024"),
        ("1.5 * 2", "3"),
    ])
    def test_evaluates(self, calculator: CalculatorTool, expression: str, expected: str):
        result = calculator.invoke({"expression": expression}, ToolContext())
        assert result.ok is True
        assert result.value == expected

    def test_format_number(self):
        assert format_number(1800.0) == "1800"
        assert format_number(2.5) == "2.5"
        assert format_number(1 / 3) == "0.333333"


class TestSecurity:
    @pytest.mark.parametrize("expression", [
        "__import__('os').system('echo pwned')",
        "open('/etc/passwd').read()",
        "(1).__class__.__bases__",
        "lambda: 1",
        "[x for x in range(10)]",
        "a + b",
        "1 if True else 2",
        "print(1)",
    ])
    def test_rejects_non_arithmetic_syntax(self, calculator: CalculatorTool, expression: str):
        result = calculator.invoke({"expression": expression}, ToolContext())
        assert result.ok is False
        assert result.error

    @pytest.mark.parametrize("expression", [
        "1/0",
        "1 % 0",
        "1 // 0",
    ])
    def test_rejects_division_by_zero(self, calculator: CalculatorTool, expression: str):
        result = calculator.invoke({"expression": expression}, ToolContext())
        assert result.ok is False
        assert "0" in (result.error or "")

    def test_rejects_huge_power(self):
        with pytest.raises(ExpressionNotAllowed):
            safe_eval("9 ** 999999")

    def test_rejects_overlong_expression(self):
        with pytest.raises(ExpressionNotAllowed):
            safe_eval("1+" * 200 + "1")

    def test_rejects_syntax_error(self):
        with pytest.raises(ExpressionNotAllowed):
            safe_eval("1 +")

    @pytest.mark.parametrize("expression", [
        "((10**12)**12)**12",       # 每个指数都不超上限，但中间结果溢出成 OverflowError
        "1e308*10",                 # 浮点溢出成 inf
        "1e309",                    # 字面量本身就超出 double 范围
        "1e308*10*0",               # inf * 0 = NaN
    ])
    def test_rejects_overflow_as_a_clear_error(self, expression: str):
        """溢出必须变成"超出允许范围"这一条明确错误。

        回归背景：旧实现让 ``OverflowError`` / ``inf`` / ``nan`` 直接穿透到
        orchestrator 的兜底 ``except Exception``，被包装成含糊的
        "工具执行异常: OverflowError"，看不出是"算不了"还是"工具坏了"；
        而 NaN 还会在 ``format_number`` 里抛 ``ValueError``。
        """
        with pytest.raises(ExpressionNotAllowed) as excinfo:
            safe_eval(expression)
        assert "超出允许范围" in str(excinfo.value)

    def test_invoke_reports_overflow_without_raising(self, calculator: CalculatorTool):
        result = calculator.invoke({"expression": "1e308*10"}, ToolContext())
        assert result.ok is False
        assert "超出允许范围" in (result.error or "")

    def test_format_number_tolerates_non_finite(self):
        assert format_number(float("nan")) == "无法表示的数值（超出范围）"
        assert format_number(float("inf")) == "无法表示的数值（超出范围）"


    def test_missing_argument(self, calculator: CalculatorTool):
        result = calculator.invoke({}, ToolContext())
        assert result.ok is False
        assert "expression" in (result.error or "")

    def test_schema_declares_expression_required(self):
        assert CalculatorTool.param_schema["required"] == ["expression"]
        assert CalculatorTool.param_schema["additionalProperties"] is False

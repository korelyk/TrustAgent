"""计算器工具：用 AST 白名单求值，而不是 ``eval()``。

**为什么不能用 ``eval``**：``eval("__import__('os').system('...')")`` 就是一条远程命令执行。
即使包一层正则过滤也很难做对。正确做法是：把表达式解析成 AST，只允许"数字 + 四则运算 + 括号"
这几种节点，其余一律拒绝。这样从语法层面就杜绝了调用任意函数/访问任意属性的可能。

同时加了三个资源保护：

* 表达式长度上限（防止构造超长表达式做 ReDoS/CPU 消耗）；
* 幂运算指数上限（``9**9**9`` 会让解释器直接卡死）；
* 结果绝对值上限（返回一个 10 万位数也属于资源滥用）。
"""

from __future__ import annotations

import ast
import math
import operator
from typing import Any

from app.tools.base import ToolContext, ToolResult

MAX_EXPRESSION_LENGTH = 200
MAX_POWER_EXPONENT = 12
MAX_ABS_RESULT = 1e15

_BINARY_OPS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
}
_UNARY_OPS = {ast.UAdd: operator.pos, ast.USub: operator.neg}


class ExpressionNotAllowed(ValueError):
    """表达式包含不允许的语法。"""


def safe_eval(expression: str) -> float:
    """只接受数字与四则运算；任何其他语法直接拒绝。"""
    if len(expression) > MAX_EXPRESSION_LENGTH:
        raise ExpressionNotAllowed(f"表达式过长（上限 {MAX_EXPRESSION_LENGTH} 字符）")
    try:
        tree = ast.parse(expression, mode="eval")
    except SyntaxError as exc:
        raise ExpressionNotAllowed("表达式语法错误") from exc

    try:
        result = _evaluate(tree.body)
    except OverflowError as exc:
        # 浮点乘/加也可能溢出成 inf（例如 1e308 * 10），或整数运算溢出成 OverflowError
        raise ExpressionNotAllowed("计算结果超出允许范围") from exc
    if not math.isfinite(result):
        # inf / -inf / nan 都不能序列化成合法 JSON，统一按"超范围"拒绝
        raise ExpressionNotAllowed("计算结果超出允许范围")
    if abs(result) > MAX_ABS_RESULT:
        raise ExpressionNotAllowed("计算结果超出允许范围")
    return result


def _evaluate(node: ast.AST) -> float:
    if isinstance(node, ast.Constant):
        if isinstance(node.value, bool) or not isinstance(node.value, (int, float)):
            raise ExpressionNotAllowed("只允许数字常量")
        return float(node.value)
    if isinstance(node, ast.BinOp):
        if isinstance(node.op, ast.Pow):
            exponent = _evaluate(node.right)
            if abs(exponent) > MAX_POWER_EXPONENT:
                raise ExpressionNotAllowed(f"幂运算指数过大（上限 {MAX_POWER_EXPONENT}）")
            try:
                return float(_evaluate(node.left)) ** float(exponent)
            except OverflowError as exc:
                # ``((10**12)**12)**12`` 这类式子：指数各自都不超上限，但**中间结果**会溢出。
                # 不捕获的话 OverflowError 会穿透到 orchestrator 的兜底 except，
                # 变成含糊的"工具执行异常: OverflowError"，用户看不出是"算不了"还是"工具坏了"。
                raise ExpressionNotAllowed("计算结果超出允许范围") from exc
        handler = _BINARY_OPS.get(type(node.op))
        if handler is None:
            raise ExpressionNotAllowed(f"不支持的运算符: {type(node.op).__name__}")
        left, right = _evaluate(node.left), _evaluate(node.right)
        if handler in (operator.truediv, operator.floordiv, operator.mod) and right == 0:
            raise ExpressionNotAllowed("除数不能为 0")
        return float(handler(left, right))
    if isinstance(node, ast.UnaryOp):
        handler = _UNARY_OPS.get(type(node.op))
        if handler is None:
            raise ExpressionNotAllowed(f"不支持的一元运算符: {type(node.op).__name__}")
        return float(handler(_evaluate(node.operand)))
    raise ExpressionNotAllowed(f"表达式包含不允许的语法: {type(node).__name__}")


def format_number(value: float) -> str:
    """整数不带小数点；浮点保留最多 6 位并去掉多余的 0。"""
    if not math.isfinite(value):
        # 正常路径已由 safe_eval 拦住非有限值；这里只是不让格式化函数自己抛异常
        return "无法表示的数值（超出范围）"
    if value == int(value):
        return str(int(value))
    return f"{value:.6f}".rstrip("0").rstrip(".")


class CalculatorTool:
    name = "calculator"
    description = "四则运算计算器（仅数字与 + - * / % ** 与括号）"
    side_effect = False
    param_schema: dict[str, Any] = {
        "type": "object",
        "properties": {
            "expression": {
                "type": "string",
                "minLength": 1,
                "maxLength": MAX_EXPRESSION_LENGTH,
                "description": "算术表达式，例如 (128 + 472) * 3",
            }
        },
        "required": ["expression"],
        "additionalProperties": False,
    }

    def invoke(self, arguments: dict[str, Any], context: ToolContext) -> ToolResult:
        expression = str(arguments.get("expression", "")).strip()
        if not expression:
            return ToolResult(ok=False, value="", error="缺少参数 expression")
        try:
            return ToolResult(ok=True, value=format_number(safe_eval(expression)))
        except ExpressionNotAllowed as exc:
            return ToolResult(ok=False, value="", error=str(exc))
        except ZeroDivisionError:
            return ToolResult(ok=False, value="", error="除数不能为 0")

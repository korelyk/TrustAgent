"""Trace ID 白名单校验测试。

回归背景：```X-Trace-Id``` 是外部可控的请求头，而它会被写进日志与安全事件
（```observability/logging.py``` 从 contextvar 取值）。旧实现直接原样采用：

* 换行符 → **日志注入**：一条请求能伪造出一整行看似正常的日志（log forging）；
* 超长值 → 撑爆日志行与数据库列；
* 任意不可见字符 → 让 ```grep <traceId>``` 这类排查手段失效。

Java 控制面早在 ```TraceIdFilter``` 里做了 8~64 位 ```[A-Za-z0-9_-]``` 的白名单，
执行面此前没有对等校验 —— 两侧规则不一致本身就是隐患：
控制面接受而执行面拒绝时，"同一个请求在两个服务里 traceId 不同"会让跨语言排查失效。

修复方式：不合法就**新生成**一个（trace id 只是观测手段，不该让业务请求失败）。
"""

from __future__ import annotations

import re

import pytest

from app.observability.trace import (
    get_trace_id,
    sanitize_trace_id,
    set_trace_id,
    trace_scope,
)

HEX32 = re.compile(r"^[0-9a-f]{32}$")


class TestSanitizeTraceId:
    def test_keeps_a_well_formed_id_unchanged(self):
        good = "0123456789abcdef0123456789abcdef"
        assert sanitize_trace_id(good) == good

    def test_keeps_ids_at_both_length_boundaries(self):
        # 8 位是最短合法值，64 位是最长合法值
        assert sanitize_trace_id("abcdefgh") == "abcdefgh"
        assert sanitize_trace_id("a" * 64) == "a" * 64

    @pytest.mark.parametrize("candidate", [
        None,
        "",
        "   ",
        "short7c",  # 7 位，短于下限
        "a" * 65,   # 65 位，长于上限
    ])
    def test_rejects_empty_and_out_of_range(self, candidate):
        assert HEX32.match(sanitize_trace_id(candidate)), "不合法输入必须回退成新生成的 32 位 hex"

    @pytest.mark.parametrize("candidate", [
        "abc\ndef",            # 换行 —— 日志注入
        "abc\rdef",            # 回车 —— 日志注入
        "abc\tdef",            # 制表符
        "trace id with space",  # 空格
        "trace;drop",           # 分隔符
        "</script>",            # 尖括号
        "trace\u200bid",       # 零宽字符
        "traäceid",             # 非 ASCII
        "../../etc/passwd",     # 路径穿越形态
    ])
    def test_rejects_log_injection_and_special_characters(self, candidate):
        result = sanitize_trace_id(candidate)
        assert result != candidate, f"危险输入 {candidate!r} 不应被原样采用"
        assert HEX32.match(result)

    def test_never_raises_for_arbitrary_text(self):
        """任何输入都不应抛异常：trace id 的校验失败不该变成 500。"""
        for candidate in ("\x00", "\n" * 100, "😀" * 40, "a" * 10000, "中文" * 50):
            assert HEX32.match(sanitize_trace_id(candidate))


class TestSetTraceId:
    def test_stores_a_sanitized_value_in_context(self):
        trace_id = set_trace_id("bad\nvalue")
        assert HEX32.match(trace_id)
        assert get_trace_id() == trace_id

    def test_trace_scope_restores_the_previous_value(self):
        outer = set_trace_id("0123456789abcdef")
        with trace_scope("fedcba9876543210") as inner:
            assert inner == "fedcba9876543210"
            assert get_trace_id() == inner
        assert get_trace_id() == outer

    def test_worker_message_trace_id_goes_through_the_same_gate(self):
        """评测任务里的 traceId 来自队列消息（也是外部输入），同样要过白名单。"""
        trace_id = set_trace_id("evil\nX-Injected: yes")
        assert "\n" not in trace_id
        assert HEX32.match(trace_id)

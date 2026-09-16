"""Trace ID 上下文。

用 ``contextvars`` 而不是全局变量：FastAPI 的每个请求可能在不同 task 里执行，
contextvar 能保证并发请求之间的 trace 互不串号（全局变量在多线程/异步下会互相覆盖）。
"""

from __future__ import annotations

import re
import uuid
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Iterator

_trace_id: ContextVar[str] = ContextVar("trustagent_trace_id", default="-")

# 与 Java 控制面的 TraceIdFilter 保持**同一套规则**：8~64 位 [A-Za-z0-9_-]。
#
# 为什么执行面也要校验（控制面已经过滤过一次）：
#   1. 执行面是可以被直接调用的（内部 Token 场景、排障、另一个调用方）；
#   2. 校验不通过就回退生成，成本为零，但少了它就会把**外部可控字符串**
#      写进日志与安全事件。换行符足以伪造出一整行看似正常的日志
#      （日志注入 / log forging），超长值则能把日志行撑爆。
#   两侧规则不一致本身也是隐患：控制面接受而执行面拒绝时，
#   "同一个请求在两个服务里 traceId 不同"会让跨语言排查彻底失效。
_SAFE_TRACE_ID = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


def sanitize_trace_id(value: str | None) -> str:
    """返回可安全写入日志的 trace id；不合法时**新生成**一个。

    刻意不抛异常：trace id 只是观测手段，不应当让业务请求失败。
    """
    if isinstance(value, str) and _SAFE_TRACE_ID.fullmatch(value):
        return value
    return uuid.uuid4().hex


def set_trace_id(value: str | None) -> str:
    """设置当前上下文的 trace id 并返回它（已通过白名单校验）。"""
    trace_id = sanitize_trace_id(value)
    _trace_id.set(trace_id)
    return trace_id


def get_trace_id() -> str:
    return _trace_id.get()


@contextmanager
def trace_scope(value: str | None) -> Iterator[str]:
    """在 with 块内设置 trace id，退出后恢复原值（避免污染外层上下文）。

    实现注意：这里**只 set 一次**，用同一个 token reset。
    旧写法是 ``token = _trace_id.set(set_trace_id(value))`` —— ``set_trace_id`` 内部
    已经 set 过一次并丢弃了它自己的 token，外层再 set 又产生第二个 token，
    结果 reset 恢复的是"内层那次 set 之前的值"（即内层值本身），
    退出 with 后 trace id **仍然是内层的**，注释承诺的"恢复原值"根本没生效。
    这个缺陷由 tests/test_trace_id.py::test_trace_scope_restores_the_previous_value 钉住。
    """
    trace_id = sanitize_trace_id(value)
    token = _trace_id.set(trace_id)
    try:
        yield trace_id
    finally:
        _trace_id.reset(token)

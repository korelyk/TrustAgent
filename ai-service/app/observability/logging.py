"""结构化日志。

输出形如::

    2026-09-15T10:20:30.123Z INFO  traceId=3d9b1f6c... event=tool.denied tool=send_notification rule=TOOL_NOT_ALLOWED

为什么不用 JSON：本项目要"人能直接看出问题"，用 ``key=value`` 单行格式在终端里比 JSON 更好读；
同时保留了按 ``key=`` 直接 grep 的能力，足以支撑演示与排查。
若将来接入 Loki/ES，把 formatter 换成 JSON 即可，调用方 ``kv()`` 不用改。
"""

from __future__ import annotations

import logging
import sys

from app.observability.trace import get_trace_id


class TraceIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id = get_trace_id()
        return True


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(
        fmt="%(asctime)s %(levelname)-5s traceId=%(trace_id)s %(name)s %(message)s",
        datefmt="%Y-%m-%dT%H:%M:%S%z",
    ))
    handler.addFilter(TraceIdFilter())

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())

    # uvicorn 的 access log 噪音较大，且其自带格式会绕过我们的 traceId 过滤器
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)


def kv(**fields: object) -> str:
    """把字段拼成 ``k=v`` 形式；None 值输出为 ``k=null``，便于断言"字段确实存在"。"""
    parts = []
    for key, value in fields.items():
        text = "null" if value is None else str(value)
        text = text.replace("\n", "\\n").replace("\r", "\\r")
        if " " in text:
            text = f'"{text}"'
        parts.append(f"{key}={text}")
    return " ".join(parts)

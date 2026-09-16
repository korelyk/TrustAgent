"""工具接口。

**所有内置工具都不得触碰真实资源**：不访问真实文件系统路径（只能读项目内的演示文档目录）、
不发真实邮件/短信、不请求公网。这是项目约束，也是"演示系统可以随便跑"的前提。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable


@dataclass(frozen=True)
class ToolResult:
    ok: bool
    value: str
    raw: Any = None
    error: str | None = None
    #: True 表示返回值来自不可信来源（例如检索到的文档正文），需要二次安检
    untrusted: bool = False


@dataclass(frozen=True)
class ToolContext:
    """工具执行上下文。目前只有域名白名单，将来可扩展超时、配额等。"""

    allowed_domains: tuple[str, ...] = field(default_factory=tuple)


@runtime_checkable
class Tool(Protocol):
    name: str
    description: str
    side_effect: bool
    param_schema: dict[str, Any]

    def invoke(self, arguments: dict[str, Any], context: ToolContext) -> ToolResult:
        ...

"""本地 Mock Outbox：模拟"副作用"落地的过程。

真实系统里"发通知"是不可逆副作用（邮件发出去就收不回来），因此需要：
确认机制 + 留痕 + 幂等。演示环境用本地 Outbox 来体现这些设计，而**不真的发消息**。

写入方式：内存列表 + 追加写 JSONL 文件（文件可选）。
用追加写（``a``）而不是重写，避免并发写互相覆盖；JSONL 也便于用 grep/脚本检查。
"""

from __future__ import annotations

import json
import threading
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.tools.base import ToolContext, ToolResult


@dataclass
class OutboxEntry:
    id: str
    channel: str
    recipient: str
    message: str
    created_at: str
    delivered: bool = False
    note: str = "模拟投递，未发送真实邮件/短信"


class MockOutbox:
    def __init__(self, file_path: Path | None = None) -> None:
        self._file_path = file_path
        self._entries: list[OutboxEntry] = []
        self._lock = threading.Lock()

    def append(self, *, channel: str, recipient: str, message: str) -> OutboxEntry:
        entry = OutboxEntry(
            id=f"outbox-{uuid.uuid4().hex[:12]}",
            channel=channel,
            recipient=recipient,
            message=message,
            created_at=datetime.now(timezone.utc).isoformat(),
        )
        with self._lock:
            self._entries.append(entry)
            if self._file_path is not None:
                self._file_path.parent.mkdir(parents=True, exist_ok=True)
                with self._file_path.open("a", encoding="utf-8") as handle:
                    handle.write(json.dumps(asdict(entry), ensure_ascii=False) + "\n")
        return entry

    def entries(self) -> list[OutboxEntry]:
        with self._lock:
            return list(self._entries)

    def count(self) -> int:
        with self._lock:
            return len(self._entries)

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()


class SendNotificationTool:
    name = "send_notification"
    description = "写入本地 Mock Outbox（有副作用；默认不启用，且受 confirmSideEffects 约束）"
    side_effect = True
    param_schema: dict[str, Any] = {
        "type": "object",
        "properties": {
            "channel": {"type": "string", "enum": ["email", "sms", "webhook"]},
            "recipient": {"type": "string", "minLength": 1, "maxLength": 64},
            "message": {"type": "string", "minLength": 1, "maxLength": 500},
        },
        "required": ["channel", "recipient", "message"],
        "additionalProperties": False,
    }

    def __init__(self, outbox: MockOutbox) -> None:
        self._outbox = outbox

    def invoke(self, arguments: dict[str, Any], context: ToolContext) -> ToolResult:
        channel = str(arguments.get("channel", "")).strip()
        recipient = str(arguments.get("recipient", "")).strip()
        message = str(arguments.get("message", "")).strip()
        if not channel or not recipient or not message:
            return ToolResult(ok=False, value="", error="channel/recipient/message 均为必填")
        entry = self._outbox.append(channel=channel, recipient=recipient, message=message)
        return ToolResult(
            ok=True,
            value=f"已写入本地 Mock Outbox（id={entry.id}, channel={channel}, recipient={recipient}），未发送真实消息。",
            raw=asdict(entry),
        )

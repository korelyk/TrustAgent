"""检测器统一接口。

把"检测"抽象成 ``Detector`` 协议，是为了**将来换成分类模型时不必改调用方**：

* 现在的实现：``InjectionDetector``（正则 + 规范化）、``PiiDetector``（正则 + 校验位）。
* 阶段4 的适配器：``PresidioDetector``、``ClassifierDetector``（加载一个小分类模型）。
* 只要满足 ``name`` + ``detect()`` 两个约束，输入/输出安检流水线就完全不用动。

这也是需求里"第一版用可解释规则、但接口要能替换为分类模型"的落地方式。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

ACTION_BLOCK = "BLOCK"
ACTION_MASK = "MASK"
ACTION_LOG = "LOG"

SEVERITY_LOW = "LOW"
SEVERITY_MEDIUM = "MEDIUM"
SEVERITY_HIGH = "HIGH"


@dataclass(frozen=True)
class Finding:
    """一次命中。``evidence`` 是**未脱敏**的原始片段，落库前必须再脱敏。"""

    rule_id: str
    category: str
    severity: str
    action: str
    detector: str
    evidence: str
    description: str = ""

    def key(self) -> tuple[str, str]:
        """去重键：同一规则 + 同一类目只报一次，避免一份文本命中 10 次刷屏。"""
        return (self.rule_id, self.category)


@runtime_checkable
class Detector(Protocol):
    """检测器协议。实现类必须提供 ``name`` 与 ``detect``。"""

    name: str

    def detect(self, text: str, *, source: str = "input",
               system_prompt: str | None = None) -> list[Finding]:
        """``source`` 取 ``input`` / ``tool_output`` / ``model_output``。"""
        ...

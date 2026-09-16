"""工具调用前的策略强制校验。

**这是整个项目最重要的一段代码**，因为它体现了核心论点：

> 安全边界必须落在"执行工具之前的那一行代码"上，而不是写在 Prompt 里提醒模型。

顺序（先便宜后昂贵，先致命后宽松）：

1. 工具是否存在（不存在的工具名不可能被执行）
2. 是否在白名单内 → ``TOOL_NOT_ALLOWED``
3. 是否超出单次任务的调用次数上限 → ``TOOL_CALL_LIMIT_EXCEEDED``
4. 有副作用的工具是否需要确认 → ``SIDE_EFFECT_CONFIRMATION_REQUIRED``
5. 参数是否符合 JSON Schema → ``PARAM_SCHEMA_VIOLATION``
6. 参数里是否出现被禁止的敏感字段名 → ``DENIED_PARAM_PRESENT``
7. 参数里的 URL 域名是否在白名单内 → ``DOMAIN_NOT_ALLOWED``

只有 1~7 全部通过，才会真正调用工具。任意一步失败都会：
记录一条安全事件 + 返回"被拒绝"的工具调用记录，并且**不执行**工具。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlparse

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

from app.guardrails.events import (
    DENIED_PARAM_PRESENT,
    DOMAIN_NOT_ALLOWED,
    PARAM_SCHEMA_VIOLATION,
    SIDE_EFFECT_CONFIRMATION_REQUIRED,
    TOOL_CALL_LIMIT_EXCEEDED,
    TOOL_NOT_ALLOWED,
)
from app.guardrails.output_guard import domain_allowed
from app.schemas import ToolPolicy

_URL_PATTERN = re.compile(r"https?://[^\s\)\]<>，。；\"']+", re.IGNORECASE)
#: 参数名里出现这些片段即视为"敏感参数"（配合策略里的 deniedParamRules 一起用）
_SENSITIVE_NAME_HINTS = ("password", "passwd", "secret", "token", "apikey", "api_key", "credential")


@dataclass(frozen=True)
class PolicyDecision:
    allowed: bool
    rule_id: str | None = None
    event_type: str | None = None
    reason: str | None = None


ALLOW = PolicyDecision(allowed=True)


class ToolPolicyEnforcer:
    """无状态，可安全复用。"""

    def check(self, *, tool_name: str, arguments: dict[str, Any], allowed_tools: list[str],
              tool_exists: bool, side_effect: bool, param_schema: dict[str, Any] | None,
              policy: ToolPolicy, call_index: int) -> PolicyDecision:
        """``allowed_tools`` 来自控制面下发的应用白名单（不是模型输出）。"""
        # 总开关关闭 = 评测里的"无防护"对照组，所有检查一律跳过
        if not policy.guard_enabled:
            return ALLOW

        if not tool_exists:
            return PolicyDecision(False, "TOOL_UNKNOWN", TOOL_NOT_ALLOWED,
                                  f"工具不存在: {tool_name}")

        if tool_name not in (allowed_tools or []):
            return PolicyDecision(False, "TOOL_NOT_ALLOWED", TOOL_NOT_ALLOWED,
                                  f"工具 {tool_name} 不在该应用的白名单内")

        if call_index >= self._max_tool_calls(policy):
            return PolicyDecision(False, "TOOL_CALL_LIMIT", TOOL_CALL_LIMIT_EXCEEDED,
                                  f"本次任务最多允许调用 {self._max_tool_calls(policy)} 次工具")

        if side_effect and policy.confirm_side_effects:
            return PolicyDecision(False, "SIDE_EFFECT_CONFIRMATION", SIDE_EFFECT_CONFIRMATION_REQUIRED,
                                  f"工具 {tool_name} 会产生副作用，策略要求人工确认后才可执行")

        schema_error = self._validate_schema(arguments, param_schema)
        if schema_error:
            return PolicyDecision(False, "PARAM_SCHEMA", PARAM_SCHEMA_VIOLATION, schema_error)

        denied = self._find_denied_param(arguments, policy)
        if denied:
            return PolicyDecision(False, "DENIED_PARAM", DENIED_PARAM_PRESENT,
                                  f"参数 {denied} 属于策略禁止的敏感字段")

        domain = self._find_disallowed_domain(arguments, policy)
        if domain:
            return PolicyDecision(False, "DOMAIN_NOT_ALLOWED", DOMAIN_NOT_ALLOWED,
                                  f"参数中的域名 {domain} 不在白名单内")

        return ALLOW

    # ------------------------------------------------------------------ 内部

    @staticmethod
    def _max_tool_calls(policy: ToolPolicy) -> int:
        # 执行面自己的硬上限兜底：即使控制面把上限配成 1000，也不会真的跑 1000 次
        return max(1, min(policy.max_tool_calls, 10))

    @staticmethod
    def _validate_schema(arguments: dict[str, Any], param_schema: dict[str, Any] | None) -> str | None:
        if not param_schema:
            return None
        try:
            Draft202012Validator.check_schema(param_schema)
        except SchemaError:
            # Schema 本身写错了属于配置问题，此时不应放行工具
            return "工具参数 Schema 定义非法（配置错误）"
        validator = Draft202012Validator(param_schema)
        errors = sorted(validator.iter_errors(arguments), key=lambda e: list(e.path))
        if not errors:
            return None
        first = errors[0]
        location = "/".join(str(part) for part in first.path) or "(root)"
        return f"参数不符合 Schema：{location} {first.message}"

    @staticmethod
    def _iter_named_params(arguments: Any, prefix: str = ""):
        if isinstance(arguments, dict):
            for key, value in arguments.items():
                path = f"{prefix}.{key}" if prefix else str(key)
                yield path, str(key), value
                yield from ToolPolicyEnforcer._iter_named_params(value, path)
        elif isinstance(arguments, list):
            for index, item in enumerate(arguments):
                yield from ToolPolicyEnforcer._iter_named_params(item, f"{prefix}[{index}]")

    def _find_denied_param(self, arguments: dict[str, Any], policy: ToolPolicy) -> str | None:
        denied = {rule.strip().lower() for rule in policy.denied_param_rules if rule and rule.strip()}
        if not denied:
            return None
        for path, name, _value in self._iter_named_params(arguments):
            lowered = name.lower()
            if lowered in denied or any(hint in lowered for hint in _SENSITIVE_NAME_HINTS):
                return path
        return None

    @staticmethod
    def _iter_leaf_values(arguments: Any):
        """深度遍历参数，只产出**叶子节点**的值。

        为什么必须只取叶子：`:meth:`_iter_named_params` 会把 list 本身也产出一遍，
        而域名检查只对 ``str`` 生效。旧写法遇到 ``{"urls": ["http://evil.example.com"]}``
        时，看到的是 ``value = ["http://evil.example.com"]``（一个 list），
        ``isinstance(value, str)`` 为假 → ``continue`` → **整个白名单形同虚设**。
        实测：字典形式的 url 会被拦，列表形式的 url 直接放行。
        """
        if isinstance(arguments, dict):
            for value in arguments.values():
                yield from ToolPolicyEnforcer._iter_leaf_values(value)
        elif isinstance(arguments, (list, tuple, set)):
            for item in arguments:
                yield from ToolPolicyEnforcer._iter_leaf_values(item)
        else:
            yield arguments

    def _find_disallowed_domain(self, arguments: dict[str, Any], policy: ToolPolicy) -> str | None:
        if not policy.domain_whitelist:
            return None
        # 用叶子遍历而不是 _iter_named_params：后者对 list 的每个元素产出的是元素本身，
        # 元素若仍是 list/dict 就会被 isinstance(str) 过滤掉（见 _iter_leaf_values 的说明）。
        for value in self._iter_leaf_values(arguments):
            if not isinstance(value, str):
                continue
            for url in _URL_PATTERN.findall(value):
                host = urlparse(url).hostname
                if host and not domain_allowed(host.lower(), policy.domain_whitelist):
                    return host
        return None

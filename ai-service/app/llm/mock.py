"""确定性的 Mock LLM。

## 为什么需要它

1. **可测**：同样的输入永远得到同样的决策，测试断言不会因模型随机性而抖动。
2. **零依赖**：不需要 API Key、不联网、不下载权重，符合项目约束。
3. **能演示风险**：它**故意不自我审查**——用户让它发通知，它就提出调用 ``send_notification``，
   哪怕这个工具不在白名单里。

## 第 3 点很重要（面试考点）

有人会问："Mock 模型为什么不自己判断该不该调用？"
因为**把安全寄托在模型自觉上本身就是错的**。真实模型可以被提示注入绕开；
一个"乖乖听话"的 Mock 会让整套策略层看起来毫无必要，也会掩盖真实风险。
因此 Mock 的行为是"只按用户意图行事"，安全判断完全交给 ``ToolPolicyEnforcer`` 与
``GuardPipeline``。这样在"防护关闭"的对照组里攻击才会真的成功，指标才有意义。
"""

from __future__ import annotations

import re
from typing import Any

from app.llm.base import LlmDecision, ToolCallRequest

# 意图关键词（按优先级排列：先看最"危险"的意图）
_NOTIFICATION_KEYWORDS = ("通知", "发送", "群发", "邮件", "提醒", "下发", "notify", "notification", "email", "send")
_DOC_KEYWORDS = ("文档", "手册", "说明", "搜索", "查找", "检索", "资料", "知识库", "doc", "docs", "document", "manual")
_TRAFFIC_KEYWORDS = ("交通", "车流", "流量", "车速", "拥堵", "道路", "环路", "指标", "traffic", "speed", "congestion", "road")
_CALC_KEYWORDS = ("计算", "算一下", "算算", "等于", "求和", "乘积", "calculate", "compute", "what is", "sum")

#: 道路名必须捕获**完整名称**（"二环路" 而不是 "二环"）。
#:
#: 曾经的写法是 ``(二环|三环|四环|...)``，只捕获到 "二环"；
#: 而 ``query_traffic_metrics`` 的 ``road`` 是枚举（只接受 "二环路"），
#: 于是 Mock 生成的参数违反了**工具自己的 Schema**，被策略层判 ``PARAM_SCHEMA_VIOLATION``。
#: 外在表现非常具有误导性：两条 NORMAL 用例被算成"误拦"，看起来像策略过严，
#: 实际是 Mock 的参数错了 —— 策略层拒绝非法参数恰恰是正确行为。
#: 这个缺陷是跑完 100 条评测、逐条归因误报之后才定位到的。
_ROAD_PATTERN = re.compile(r"((?:二环|三环|四环)路|滨海大道|迎宾路|海秀路|ring\s*[234])")
_DATE_PATTERN = re.compile(r"(今天|昨天|前天|上周|本周|\d{4}-\d{2}-\d{2}|today|yesterday)")

#: 英文写法 → 目录里的中文路名（见 :func:`MockLLMProvider._extract_road`）
_RING_ALIASES = {"ring2": "二环路", "ring3": "三环路", "ring4": "四环路"}


class MockLLMProvider:
    """确定性意图识别 + 模板化回答。"""

    name = "mock"

    def __init__(self, model: str = "mock-llm-v1") -> None:
        self.model = model

    def decide(self, *, system_prompt: str | None,
               user_prompt: str,
               context: str | None,
               available_tools: list[str],
               tool_schemas: dict[str, str],
               history: list[dict[str, Any]]) -> LlmDecision:
        # 已有工具结果 → 生成最终回答（第二轮），保证链路一定收敛、不会无限循环
        if history:
            last = history[-1]
            if last.get("type") == "tool_result":
                return LlmDecision(
                    kind="final",
                    text=self._compose_answer(last),
                    model=self.model,
                )
            if last.get("type") == "tool_denied":
                return LlmDecision(
                    kind="final",
                    text="抱歉，我尝试调用的工具被安全策略阻止了，无法完成该操作。",
                    model=self.model,
                )

        haystack = f"{user_prompt} {context or ''}".lower()

        if self._matches(haystack, _NOTIFICATION_KEYWORDS):
            return LlmDecision(kind="tool_call", model=self.model, tool_call=ToolCallRequest(
                tool_name="send_notification",
                arguments={
                    "channel": "email",
                    "recipient": self._extract_recipient(user_prompt),
                    "message": self._extract_message(user_prompt),
                },
                reasoning="用户要求发送通知",
            ))

        if self._matches(haystack, _DOC_KEYWORDS):
            return LlmDecision(kind="tool_call", model=self.model, tool_call=ToolCallRequest(
                tool_name="search_local_docs",
                arguments={"query": user_prompt.strip()[:80] or "项目介绍"},
                reasoning="用户想查阅项目文档",
            ))

        if self._matches(haystack, _TRAFFIC_KEYWORDS):
            return LlmDecision(kind="tool_call", model=self.model, tool_call=ToolCallRequest(
                tool_name="query_traffic_metrics",
                arguments={
                    "metric": "avg_speed" if "速" in user_prompt or "speed" in haystack else "volume",
                    "road": self._extract_road(user_prompt),
                    "date": self._extract_date(user_prompt),
                },
                reasoning="用户想查询交通指标",
            ))

        expression = self._extract_expression(user_prompt)
        if expression and (self._matches(haystack, _CALC_KEYWORDS) or self._looks_like_math(user_prompt)):
            return LlmDecision(kind="tool_call", model=self.model, tool_call=ToolCallRequest(
                tool_name="calculator",
                arguments={"expression": expression},
                reasoning="用户需要数值计算",
            ))

        return LlmDecision(kind="final", model=self.model,
                           text=self._fallback_answer(user_prompt, available_tools))

    # ------------------------------------------------------------------ 内部

    @staticmethod
    def _matches(haystack: str, keywords: tuple[str, ...]) -> bool:
        return any(keyword in haystack for keyword in keywords)

    @staticmethod
    def _extract_expression(prompt: str) -> str | None:
        """提取最长的**括号平衡**算术表达式。

        为什么不能直接用一条正则扫：``(128 + 472) * 3`` 这种式子，
        从第一个数字开始扫会得到 ``128 + 472)``，括号不平衡，交给计算器必然报错。
        因此改为从左到右扫描并跟踪括号深度，只保留"深度为 0 且以数字结尾"的前缀。
        """
        allowed = set("0123456789+-*/(). ")
        best = ""
        index = 0
        while index < len(prompt):
            start_char = prompt[index]
            if start_char not in allowed or not (start_char.isdigit() or start_char == "("):
                index += 1
                continue

            depth = 0
            buffer = ""
            last_valid = ""
            cursor = index
            while cursor < len(prompt) and prompt[cursor] in allowed:
                char = prompt[cursor]
                if char == "(":
                    depth += 1
                elif char == ")":
                    if depth == 0:
                        break
                    depth -= 1
                buffer += char
                stripped = buffer.strip()
                if depth == 0 and stripped and stripped[-1].isdigit():
                    last_valid = stripped
                cursor += 1

            # 至少要有一个运算符，否则 "2026" 这种纯数字会被误判成算式
            if len(last_valid) <= len(best) or not any(op in last_valid for op in "+-*/"):
                index = max(cursor, index + 1)
                continue
            # 拒绝连续运算符：脱敏后的手机号 "138******00" 会因为含 '*' 被误当作算式
            if re.search(r"[+\-*/]{2,}", last_valid):
                index = max(cursor, index + 1)
                continue
            best = last_valid
            index = max(cursor, index + 1)
        return best or None

    @staticmethod
    def _looks_like_math(prompt: str) -> bool:
        return bool(re.search(r"\d\s*[-+*/]\s*\d", prompt))

    @staticmethod
    def _extract_road(prompt: str) -> str:
        match = _ROAD_PATTERN.search(prompt)
        if not match:
            return "二环路"
        raw = match.group(1).strip()
        # 英文写法要映射到目录里的中文路名：否则 "ring 2" 会原样作为 road 下发，
        # 而 road 是枚举 → PARAM_SCHEMA_VIOLATION，表现为"用英文提问的正常请求被拦下"。
        return _RING_ALIASES.get(raw.lower().replace(" ", ""), raw)

    @staticmethod
    def _extract_date(prompt: str) -> str:
        match = _DATE_PATTERN.search(prompt)
        return match.group(1) if match else "昨天"

    @staticmethod
    def _extract_recipient(prompt: str) -> str:
        if any(word in prompt for word in ("全体", "所有", "全部", "所有人", "all")):
            return "all-users"
        return "demo-user"

    @staticmethod
    def _extract_message(prompt: str) -> str:
        cleaned = prompt.strip().replace("\n", " ")
        return cleaned[:120] if cleaned else "系统通知"

    @staticmethod
    def _compose_answer(tool_result: dict[str, Any]) -> str:
        tool_name = tool_result.get("toolName")
        value = str(tool_result.get("value", "")).strip()
        if tool_name == "calculator":
            return f"计算结果是 {value}。"
        if tool_name == "query_traffic_metrics":
            return f"查询结果如下：{value}"
        if tool_name == "search_local_docs":
            return f"根据项目文档，检索到的相关片段是：\n{value}"
        if tool_name == "send_notification":
            return f"通知已按你的要求提交到本地 Mock Outbox（未发送真实邮件/短信）。{value}"
        return f"工具 {tool_name} 返回：{value}"

    @staticmethod
    def _fallback_answer(prompt: str, available_tools: list[str]) -> str:
        tools = ", ".join(available_tools) if available_tools else "（当前未启用任何工具）"
        preview = prompt.strip().replace("\n", " ")[:60]
        return (f"我是运行在 Mock 模式下的演示助手，当前可用的工具是：{tools}。"
                f"对于「{preview}」这个问题，我可以帮你做数值计算、查询项目文档或查询合成交通指标。"
                f"（未配置真实模型时，所有回答都由确定性模板生成，不代表真实模型的输出。）")

"""Agent 编排器：把"安检 → 决策 → 工具前校验 → 执行 → 输出安检"串成一次请求。

时序严格按需求第七节：

1. 输入安全检查（注入 + PII + 长度）
2. 若被阻断 → 直接返回 ``status=BLOCKED``，**模型不会被调用**
3. 模型决策（可能是工具调用，也可能是直接回答）
4. 工具调用前执行策略校验（`ToolPolicyEnforcer`）
5. 被拒绝 → 记录安全事件 + 记录"被拒绝的工具调用" + 结束
6. 被允许 → 执行工具；若工具返回值来自不可信来源 → **间接注入二次安检**
7. 检测到间接注入 → 记录事件 +（可选）证明"模型本来会被带偏" + 结束
8. 模型给出最终回答 → 输出安检（PII / 系统提示词 / HTML / URL）

第 7 步里"证明模型本来会被带偏"这一点值得说明：我们会拿被污染的内容再问一次模型，
把它的决策**只作为证据记录**，绝不执行。这样能直观展示"没有防护就会出事"，
同时不会真的把攻击动作做出来。
"""

from __future__ import annotations

import time
from typing import Any

from app.agent.policy_enforcer import ToolPolicyEnforcer
from app.config import get_settings
from app.guardrails.events import (
    PROMPT_INJECTION_INDIRECT,
    TASK_TIMEOUT,
    TOOL_NOT_ALLOWED,
    severity_of,
)
from app.guardrails.pipeline import DECISION_BLOCK, DECISION_MASK, GuardDecision, GuardPipeline
from app.guardrails.pii import mask_value
from app.llm.base import LLMProvider
from app.observability.logging import kv
from app.schemas import (
    AgentRunRequest,
    AgentRunResponse,
    GuardEventPayload,
    ToolCallPayload,
)
from app.tools.base import Tool, ToolContext

import json
import logging

logger = logging.getLogger(__name__)

STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_BLOCKED = "BLOCKED"
STATUS_FAILED = "FAILED"

#: 单次请求内最多"决策 → 工具"的轮次，防止模型陷入死循环
MAX_AGENT_ROUNDS = 4

BLOCKED_OUTPUT_TEXT = "本次请求已被安全策略阻断，未调用模型，也没有执行任何工具。"


class AgentOrchestrator:
    def __init__(self, *, llm: LLMProvider, pipeline: GuardPipeline,
                 registry: dict[str, Tool], enforcer: ToolPolicyEnforcer | None = None) -> None:
        self._llm = llm
        self._pipeline = pipeline
        self._registry = registry
        self._enforcer = enforcer or ToolPolicyEnforcer()

    def run(self, request: AgentRunRequest) -> AgentRunResponse:
        started = time.perf_counter()
        policy = request.policy
        # 任务级 deadline：取"策略上限"与"执行面硬上限"的较小值。
        #
        # 旧实现里 task_timeout_ms 与 hard_task_timeout_ms **从未被任何代码消费**：
        # 只在 schemas/config 里定义、在 /internal/config 里被读出来展示。
        # 实测把 LLM 调用 sleep 到 16.5s（策略上限 15s）仍返回 SUCCEEDED。
        # 也就是说文档与威胁模型里声明的"任务超时"控制此前并不存在。
        limits = get_settings()
        budget_ms = min(policy.task_timeout_ms or limits.hard_task_timeout_ms,
                        limits.hard_task_timeout_ms)
        budget_ms = max(1, budget_ms)
        deadline = started + budget_ms / 1000.0
        events: list[GuardEventPayload] = []
        # 本次请求跨阶段的"最终判定"：任何阶段拦过就是 BLOCK，任何阶段脱敏过就是 MASK。
        # 旧实现直接把**输入侧**的 decision 回填给 guardDecision，于是出现两类自相矛盾：
        #   * 场景 B/C：status=BLOCKED（工具被拒 / 工具输出被判定为注入）而 guardDecision=ALLOW；
        #   * 输出侧才命中的 PII/HTML：decision 是 MASK，但 guardDecision 说 ALLOW。
        # 前端 Playground、Trace 详情与冒烟脚本都展示这个字段，两处说法不一致会直接被当成 bug。
        blocked_any = False
        masked_any = False
        tool_calls: list[ToolCallPayload] = []

        # ---- 1. 输入安检 -------------------------------------------------
        # context 必须一起送检：它是用户可控字段，且会原样进入模型上下文。
        # 只检查 prompt 时，把注入放进 context 即可绕过全部输入侧检测
        # （实测：prompt 无害 + context 带注入 → guardDecision=ALLOW、零事件，
        #  模型照样调用 send_notification 并真的执行了副作用）。
        input_decision = self._pipeline.inspect_input(
            request.prompt, policy, system_prompt=request.system_prompt,
            extra_texts=[request.context] if request.context else None)
        masked_any = input_decision.decision == DECISION_MASK
        events.extend(input_decision.events)
        logger.info(kv(event="guard.input", decision=input_decision.decision,
                       categories=",".join(input_decision.categories),
                       transforms=",".join(input_decision.transforms)))

        if input_decision.blocked:
            return self._blocked(request, events, input_decision, started)

        masked_prompt = input_decision.text
        history: list[dict[str, Any]] = []
        final_text = ""
        status = STATUS_SUCCEEDED
        call_index = 0

        # ---- 2~7. 决策 / 工具前校验 / 执行 / 二次安检 -----------------------
        for _round in range(MAX_AGENT_ROUNDS):
            # 每轮开始前检查预算：超时就不再让模型继续决策，直接以 FAILED 收敛。
            if time.perf_counter() > deadline:
                status = STATUS_FAILED
                final_text = f"本次任务超过策略允许的耗时上限（{budget_ms}ms），已中止。"
                events.append(self._timeout_event(budget_ms))
                logger.warning(kv(event="task.timeout", round=_round, budgetMs=budget_ms))
                break
            decision = self._llm.decide(
                system_prompt=request.system_prompt,
                user_prompt=masked_prompt,
                context=request.context,
                available_tools=list(request.allowed_tools),
                tool_schemas=request.tool_schemas,
                history=history,
            )

            # 模型这一步本身就可能吃掉整个预算（真实 provider 是一次网络等待）。
            # 只在轮次之间检查的话，超时任务会继续拿着一个"已经过期"的结果去执行工具——
            # 侧效应一旦落地就收不回来了，所以决策返回后必须再检查一次。
            if time.perf_counter() > deadline:
                status = STATUS_FAILED
                final_text = f"本次任务超过策略允许的耗时上限（{budget_ms}ms），已中止。"
                events.append(self._timeout_event(budget_ms))
                logger.warning(kv(event="task.timeout", round=_round, budgetMs=budget_ms, phase="after_decide"))
                break

            if not decision.is_tool_call:
                final_text = decision.text or ""
                break

            tool_call = decision.tool_call
            assert tool_call is not None  # is_tool_call 保证非空
            tool = self._registry.get(tool_call.tool_name)
            tool_call_started = time.perf_counter()

            verdict = self._enforcer.check(
                tool_name=tool_call.tool_name,
                arguments=tool_call.arguments,
                allowed_tools=list(request.allowed_tools),
                tool_exists=tool is not None,
                side_effect=bool(tool.side_effect) if tool else False,
                param_schema=self._resolve_schema(tool, request.tool_schemas),
                policy=policy,
                call_index=call_index,
            )

            if not verdict.allowed:
                latency = int((time.perf_counter() - tool_call_started) * 1000)
                tool_calls.append(ToolCallPayload(
                    tool_name=tool_call.tool_name,
                    allowed=False,
                    deny_reason=verdict.reason,
                    arguments_masked=self._mask_arguments(tool_call.arguments),
                    result_masked=None,
                    latency_ms=latency,
                ))
                events.append(GuardEventPayload(
                    event_type=verdict.event_type or TOOL_NOT_ALLOWED,
                    severity="HIGH",
                    detector="tool_policy",
                    rule_id=verdict.rule_id,
                    evidence_masked=verdict.reason,
                    action_taken="BLOCK",
                ))
                logger.warning(kv(event="tool.denied", tool=tool_call.tool_name,
                                  rule=verdict.rule_id, reason=verdict.reason))
                history.append({"type": "tool_denied", "toolName": tool_call.tool_name,
                                "reason": verdict.reason})
                status = STATUS_BLOCKED
                blocked_any = True
                final_text = f"我尝试调用工具 {tool_call.tool_name}，但被安全策略阻止了：{verdict.reason}"
                break

            call_index += 1
            result = self._invoke_tool(tool, tool_call.arguments, policy)
            latency = int((time.perf_counter() - tool_call_started) * 1000)
            tool_calls.append(ToolCallPayload(
                tool_name=tool_call.tool_name,
                allowed=True,
                deny_reason=None,
                arguments_masked=self._mask_arguments(tool_call.arguments),
                result_masked=self._mask_tool_result(result.value if result.ok else result.error),
                latency_ms=latency,
            ))
            logger.info(kv(event="tool.executed", tool=tool_call.tool_name, ok=result.ok,
                           latencyMs=latency, sideEffect=tool.side_effect))

            if not result.ok:
                history.append({"type": "tool_result", "toolName": tool_call.tool_name,
                                "value": result.error or "工具执行失败", "ok": False})
                continue

            # ---- 间接提示注入：工具返回值是不可信输入 ----------------------
            if result.untrusted:
                tool_output_decision = self._pipeline.inspect_tool_output(
                    result.value, policy, system_prompt=request.system_prompt)
                if tool_output_decision.blocked:
                    events.extend(tool_output_decision.events)
                    events.append(self._evidence_of_followup(request, tool_output_decision, history))
                    status = STATUS_BLOCKED
                    blocked_any = True
                    final_text = (f"工具 {tool_call.tool_name} 返回的内容中检出提示注入指令，"
                                  f"已阻断后续动作。")
                    logger.warning(kv(event="guard.tool_output.blocked", tool=tool_call.tool_name,
                                      categories=",".join(tool_output_decision.categories)))
                    break
                events.extend(tool_output_decision.events)
            else:
                # 工具返回值不是不可信来源 → 没有二次安检，直接用原值
                tool_output_decision = None

            # 进模型上下文的是**安检后**的文本，而不是工具原始返回值。
            # 当前 ``inspect_tool_output`` 的 sanitizer 是恒等函数（它只做间接注入检测），
            # 所以这一行与直接用 ``result.value`` 等价；但一旦哪天上游给工具输出加了脱敏，
            # 这里不需要再改一次——用 decision.text 才是"安检后的那份"。
            history.append({"type": "tool_result", "toolName": tool_call.tool_name,
                            "value": (tool_output_decision.text
                                      if tool_output_decision is not None else result.value),
                            "ok": result.ok})

        if not final_text and status == STATUS_SUCCEEDED:
            # 理论上不可达（每轮要么 break 要么设置 final_text），保留兜底以免返回空字符串
            final_text = "（未产生回答：达到最大轮次限制）"
            status = STATUS_FAILED

        # ---- 8. 输出安检 -------------------------------------------------
        output_decision = self._pipeline.inspect_output(
            final_text, policy, system_prompt=request.system_prompt)
        events.extend(output_decision.events)
        if output_decision.blocked:
            blocked_any = True
            status = STATUS_BLOCKED
        if output_decision.decision == DECISION_MASK:
            masked_any = True
        output_text = output_decision.text
        if output_decision.decision != "ALLOW":
            output_decision_categories = ",".join(output_decision.categories)
            logger.info(kv(event="guard.output", decision=output_decision.decision,
                           categories=output_decision_categories))

        latency_ms = int((time.perf_counter() - started) * 1000)
        return AgentRunResponse(
            trace_id="",  # 由 API 层填充真实 traceId
            status=status,
            guard_decision=self._final_decision(blocked_any, masked_any),
            # 类目取自**全部**安全事件（输入 / 工具策略 / 工具输出 / 输出四个阶段）：
            # 只合并 input+output 的 categories 会漏掉工具策略层（场景 B）与工具输出安检（场景 C）——
            # 那两处恰好是"输入侧零命中"的拦截，漏掉就会退化成 status=BLOCKED 而 categories=[]。
            guard_categories=sorted({event.event_type for event in events}),
            input_masked=masked_prompt,
            output_masked=output_text,
            tool_calls=tool_calls,
            security_events=events,
            model=getattr(self._llm, "model", getattr(self._llm, "name", "unknown")),
            latency_ms=latency_ms,
        )

    # ------------------------------------------------------------------ 内部

    @staticmethod
    def _final_decision(blocked_any: bool, masked_any: bool) -> str:
        """本次请求的最终判定。优先级：BLOCK > MASK > ALLOW。

        与 :class:`GuardDecision` 的三值语义一致，也与 Java 控制面 ``ChatResponse.guardDecision``
        的取值范围（``ALLOW`` / ``MASK`` / ``BLOCK``）一致。
        """
        if blocked_any:
            return DECISION_BLOCK
        if masked_any:
            return DECISION_MASK
        return "ALLOW"

    @staticmethod
    def _timeout_event(budget_ms: int) -> GuardEventPayload:
        """任务级 deadline 超时的安全事件。

        两个检查点（决策前 / 决策后）共用同一份字段定义，避免两处慢慢漂移。
        严重级别取自 :data:`app.guardrails.events.SEVERITY`，不在这里写死。
        """
        return GuardEventPayload(
            event_type=TASK_TIMEOUT,
            severity=severity_of(TASK_TIMEOUT),
            detector="task_deadline",
            rule_id="TASK_TIMEOUT",
            evidence_masked=f"budgetMs={budget_ms}",
            action_taken="BLOCK",
        )

    def _blocked(self, request: AgentRunRequest, events: list[GuardEventPayload],
                 input_decision: GuardDecision, started: float) -> AgentRunResponse:
        logger.warning(kv(event="guard.input.blocked", categories=",".join(input_decision.categories)))
        return AgentRunResponse(
            trace_id="",
            status=STATUS_BLOCKED,
            guard_decision=DECISION_BLOCK,
            guard_categories=input_decision.categories,
            input_masked=self._mask_text(request.prompt),
            output_masked=BLOCKED_OUTPUT_TEXT,
            tool_calls=[],
            security_events=events,
            model=getattr(self._llm, "model", getattr(self._llm, "name", "unknown")),
            latency_ms=int((time.perf_counter() - started) * 1000),
        )

    def _evidence_of_followup(self, request: AgentRunRequest, tool_output_decision: GuardDecision,
                              history: list[dict[str, Any]]) -> GuardEventPayload:
        """记录"如果不拦，模型会做什么"，作为间接注入危害的证据（只记录，不执行）。"""
        proposal = "（模型未提出后续动作）"
        try:
            probe_history = list(history) + [{
                "type": "tool_result",
                "toolName": "untrusted_document",
                "value": tool_output_decision.text,
                "ok": True,
            }]
            decision = self._llm.decide(
                system_prompt=request.system_prompt,
                user_prompt=request.prompt,
                context=request.context,
                available_tools=list(request.allowed_tools),
                tool_schemas=request.tool_schemas,
                history=probe_history,
            )
            if decision.is_tool_call and decision.tool_call:
                proposal = f"模型在被污染内容下提出调用 {decision.tool_call.tool_name}"
        except Exception as exc:  # 证据收集失败不应影响主流程
            logger.debug(kv(event="guard.followup_probe.failed", error=str(exc)))

        return GuardEventPayload(
            event_type=PROMPT_INJECTION_INDIRECT,
            severity="HIGH",
            detector="injection_rules",
            rule_id="INJ-INDIRECT-EVIDENCE",
            evidence_masked=proposal,
            action_taken="BLOCK",
        )

    def _invoke_tool(self, tool: Tool, arguments: dict[str, Any], policy) -> Any:
        from app.tools.base import ToolResult

        try:
            return tool.invoke(arguments, ToolContext(allowed_domains=tuple(policy.domain_whitelist)))
        except Exception as exc:  # 工具抛异常不能拖垮整个请求
            logger.exception(kv(event="tool.error", tool=tool.name))
            return ToolResult(ok=False, value="", error=f"工具执行异常: {type(exc).__name__}")

    @staticmethod
    def _resolve_schema(tool: Tool | None, overrides: dict[str, str]) -> dict[str, Any] | None:
        if tool is None:
            return None
        raw = (overrides or {}).get(tool.name)
        if raw:
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                logger.warning(kv(event="tool.schema.parse_failed", tool=tool.name))
        return tool.param_schema

    @staticmethod
    def _mask_arguments(arguments: dict[str, Any]) -> str:
        """工具参数入库前脱敏：先序列化，再把敏感值替换成掩码。"""
        from app.guardrails.pii import detect_pii

        text = json.dumps(arguments, ensure_ascii=False, sort_keys=True)
        masked = text
        for match in reversed(detect_pii(text)):
            masked = masked[:match.start] + mask_value(match.value) + masked[match.end:]
        return masked

    @staticmethod
    def _mask_tool_result(value: str | None) -> str | None:
        from app.guardrails.pii import detect_pii

        if not value:
            return value
        masked = value
        for match in reversed(detect_pii(masked)):
            masked = masked[:match.start] + mask_value(match.value) + masked[match.end:]
        return masked

    @staticmethod
    def _mask_text(text: str | None) -> str:
        return AgentOrchestrator._mask_tool_result(text) or ""

package io.trustagent.chat;

import io.trustagent.app.AgentApp;
import io.trustagent.app.AgentAppService;
import io.trustagent.app.SecurityPolicy;
import io.trustagent.audit.AuditService;
import io.trustagent.chat.dto.ChatDtos;
import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.common.SensitiveDataMasker;
import io.trustagent.common.TraceContext;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.gateway.PythonAgentClient;
import io.trustagent.gateway.dto.AgentRunRequest;
import io.trustagent.gateway.dto.AgentRunResponse;
import io.trustagent.ratelimit.RateLimitService;
import io.trustagent.security.SecurityUtils;
import io.trustagent.security.UserPrincipal;
import io.trustagent.securityevent.SecurityEventType;
import io.trustagent.securityevent.SecurityEventService;
import io.trustagent.trace.AgentTrace;
import io.trustagent.trace.ToolCallRecord;
import io.trustagent.trace.TraceService;
import io.trustagent.trace.mapper.AgentTraceMapper;
import io.trustagent.trace.mapper.ToolCallRecordMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Playground 编排：控制面在一次 Agent 请求里做的全部事情。
 *
 * <pre>
 * 认证 → 属主校验 → 限流 → 输入长度校验 → 生成/透传 Trace ID
 *   → 写 agent_trace(RUNNING)
 *   → 调执行面（执行面内部：输入安检 → 决策 → 工具前策略校验 → 执行 → 输出安检）
 *   → 落库工具调用 / 安全事件 / 更新 trace → 写审计
 * </pre>
 *
 * <p>关键点：控制面**不执行任何工具**，也不解析模型的自由文本去做判断。
 * 它只负责“下发策略 + 记录事实”，这样执行面即使被完全攻陷，能做的事也被策略限死。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 与 {@code tool_call_record.tool_name varchar(64)} 对齐。 */
    private static final int TOOL_NAME_MAX_LENGTH = 64;
    /** 与 {@code tool_call_record.deny_reason varchar(255)} 对齐。 */
    private static final int DENY_REASON_MAX_LENGTH = 255;
    /** 与 {@code agent_trace.guard_categories varchar(512)} 对齐。 */
    private static final int GUARD_CATEGORIES_MAX_LENGTH = 512;

    private final AgentAppService agentAppService;
    private final PythonAgentClient pythonAgentClient;
    private final RateLimitService rateLimitService;
    private final TraceService traceService;
    private final AgentTraceMapper traceMapper;
    private final ToolCallRecordMapper toolCallRecordMapper;
    private final SecurityEventService securityEventService;
    private final AuditService auditService;
    private final TrustAgentProperties props;

    @Transactional
    public ChatDtos.ChatResponse chat(Long appId, ChatDtos.ChatRequest request) {
        UserPrincipal principal = SecurityUtils.requirePrincipal();
        long startNanos = System.nanoTime();

        AgentApp app = agentAppService.requireAccessible(appId);
        if (!AgentAppService.STATUS_ACTIVE.equals(app.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "该 Agent 应用已被禁用，无法对话");
        }

        // 限流维度：用户 + 应用。避免单个用户对单个应用打满，同时不影响他使用别的应用。
        rateLimitService.enforce("chat", principal.getId() + "|" + appId, rateLimitService.chatWindow());

        SecurityPolicy policy = agentAppService.getPolicyOrThrow(appId);
        String traceId = TraceContext.currentTraceId();

        if (request.prompt().length() > policy.getMaxInputLength()) {
            securityEventService.record(traceId, appId, SecurityEventType.INPUT_TOO_LONG, "policy_length_check",
                    "MAX_INPUT_LENGTH",
                    "len=" + request.prompt().length() + " limit=" + policy.getMaxInputLength(), "BLOCK");
            throw new BizException(ErrorCode.INPUT_TOO_LONG,
                    "输入长度 " + request.prompt().length() + " 超过策略上限 " + policy.getMaxInputLength());
        }

        int previewMax = props.getMasking().getPreviewMaxLength();
        AgentTrace trace = traceService.createRunning(appId, principal.getId(), traceId,
                SensitiveDataMasker.preview(request.prompt(), previewMax));

        List<String> allowedTools = policy.getGuardEnabled() != null && policy.getGuardEnabled()
                ? agentAppService.enabledToolNames(appId)
                : List.of();
        AgentRunRequest runRequest = AgentRunRequest.of(app, policy, request.prompt(), request.context(),
                allowedTools, agentAppService.toolSchemas(appId));

        AgentRunResponse response;
        try {
            response = pythonAgentClient.run(runRequest, traceId);
        } catch (BizException ex) {
            // 下游失败也必须留痕：否则"执行面挂了"这件事在 Trace 里查不到。
            //
            // 这里有两个必须用**独立事务**的点，否则这条留痕会静默消失：
            //   1) traceService.markFailed / auditService.recordIndependent 都是 REQUIRES_NEW；
            //   2) 紧接着的 throw ex 会让本方法（@Transactional）整体回滚。
            // 曾经这两行用的是同一个业务事务：停掉 ai-service 后发起对话，接口返回 502，
            // 而 audit_log 里查不到 AGENT_CHAT_FAILED、GET /api/traces/<traceId> 直接 404——
            // Trace 行本身也跟着回滚了。"失败"恰好是最需要被记住的事实，不能被回滚掉。
            //
            // 审计用 recordIndependent 而不是 record：成功路径仍然是 record（业务成功才留
            // AGENT_CHAT_EXECUTED，保持原设计的因果关系），只有失败路径需要"业务回滚也要留"。
            long failedLatency = elapsedMs(startNanos);
            traceService.markFailed(traceId, ex.getMessage(), failedLatency);
            auditService.recordIndependent("AGENT_CHAT_FAILED", "agent_app", String.valueOf(appId),
                    Map.of("errorCode", ex.errorCode().code(), "message", ex.getMessage()));
            throw ex;
        }

        persistOutcome(trace, appId, response, previewMax);

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("appCode", app.getCode());
        auditDetail.put("status", response.status());
        auditDetail.put("guardDecision", response.guardDecision());
        auditDetail.put("toolCalls", response.toolCalls() == null ? 0 : response.toolCalls().size());
        auditDetail.put("policyVersion", policy.getVersion());
        auditService.record("AGENT_CHAT_EXECUTED", "agent_app", String.valueOf(appId), auditDetail);

        return toResponse(response, traceId, elapsedMs(startNanos), policy.getVersion());
    }

    private void persistOutcome(AgentTrace trace, Long appId, AgentRunResponse response, int previewMax) {
        String traceId = trace.getTraceId();
        List<AgentRunResponse.ToolCall> toolCalls = response.toolCalls() == null ? List.of() : response.toolCalls();

        for (AgentRunResponse.ToolCall call : toolCalls) {
            ToolCallRecord record = new ToolCallRecord();
            record.setTraceId(traceId);
            // 列宽约束（见 db/migration/V1__baseline.sql）：tool_name varchar(64)、deny_reason varchar(255)。
            // MySQL 严格模式下超长值是**错误**而不是自动截断，而本方法跑在 @Transactional 里——
            // 一个超宽的 toolName 会让整个对话请求 500，报错还指向 SQL 约束，与真正的原因隔了一层。
            // arguments_masked / result_masked 早就做了预裁剪，这两个字段以前漏了；现在补齐，
            // 用与它们相同的 clip 语义（纯截断，不脱敏——工具名与拒绝原因是受控枚举，不含用户数据）。
            record.setToolName(clip(call.toolName(), TOOL_NAME_MAX_LENGTH));
            // 二次脱敏：即便执行面漏了，入库前仍然会掩盖敏感值
            record.setArgumentsMasked(SensitiveDataMasker.preview(call.argumentsMasked(), 2000));
            record.setResultMasked(SensitiveDataMasker.preview(call.resultMasked(), 2000));
            record.setAllowed(call.allowed());
            record.setDenyReason(clip(call.denyReason(), DENY_REASON_MAX_LENGTH));
            record.setLatencyMs((int) call.latencyMs());
            record.setCreatedAt(LocalDateTime.now());
            toolCallRecordMapper.insert(record);
        }

        // 执行面上报的安全事件**必须由控制面落库**。
        //
        // 这里曾经只写一条 debug 日志，注释说"执行面承担事件上报职责"——
        // 那句话是错的：Python 执行面的 requirements 里没有任何数据库驱动，
        // 它连不上 MySQL，只能在 HTTP 响应里把事件带回来。
        // 结果是 PII 脱敏、注入检出、工具拦截这些事件**全部被丢弃**，
        // security_event 表长期为空（只有 input_too_long 这条 Java 侧自产的事件能落库）。
        //
        // 为什么由控制面落库而不是让执行面直连数据库：
        // 控制面是唯一的真相源，它同时持有 traceId / appId / 属主校验的结果；
        // 让执行面直连 MySQL 会把"记录事实"的职责分散到两个进程，
        // 执行面一旦被攻陷就能伪造审计。所以职责划分是：
        //   执行面负责**检测**并如实回传，控制面负责**记录**。
        if (response.securityEvents() != null) {
            for (AgentRunResponse.GuardEvent event : response.securityEvents()) {
                if (event.eventType() == null || event.eventType().isBlank()) {
                    // event_type 是 NOT NULL 列。跳过而不是抛异常：
                    // 本方法在 chat() 的事务里，让一条畸形事件把整个请求回滚，
                    // 等于"执行面报了个格式错的事件，用户连对话都用不了"。
                    log.warn("执行面上报的安全事件缺少 eventType，已跳过 traceId={}", traceId);
                    continue;
                }
                // severity 也是 NOT NULL；执行面没给级别时按类型表推导（未知类型默认 MEDIUM）
                String severity = (event.severity() == null || event.severity().isBlank())
                        ? SecurityEventType.severityOf(event.eventType())
                        : event.severity();
                securityEventService.record(traceId, appId, event.eventType(), severity,
                        event.detector(), event.ruleId(), event.evidenceMasked(), event.actionTaken());
            }
        }

        trace.setStatus(response.status() == null ? AgentTrace.STATUS_SUCCEEDED : response.status());
        trace.setGuardDecision(response.guardDecision());
        trace.setGuardCategories(joinCategories(response.guardCategories()));
        trace.setOutputPreview(SensitiveDataMasker.preview(response.outputMasked(), previewMax));
        trace.setToolCallCount(toolCalls.size());
        trace.setTotalLatencyMs((int) response.latencyMs());
        traceMapper.updateById(trace);
    }

    private ChatDtos.ChatResponse toResponse(AgentRunResponse response, String traceId, long elapsedMs,
                                             int policyVersion) {
        List<ChatDtos.ToolCallView> toolCalls = (response.toolCalls() == null ? List.<AgentRunResponse.ToolCall>of()
                : response.toolCalls()).stream()
                .map(c -> new ChatDtos.ToolCallView(c.toolName(), c.allowed(), c.denyReason(),
                        c.argumentsMasked(), c.resultMasked(), c.latencyMs()))
                .toList();
        List<ChatDtos.SecurityEventView> events = (response.securityEvents() == null
                ? List.<AgentRunResponse.GuardEvent>of() : response.securityEvents()).stream()
                .map(e -> new ChatDtos.SecurityEventView(e.eventType(), e.severity(), e.detector(),
                        e.ruleId(), e.evidenceMasked(), e.actionTaken()))
                .toList();
        return new ChatDtos.ChatResponse(traceId, response.status(), response.guardDecision(),
                response.guardCategories() == null ? List.of() : response.guardCategories(),
                response.inputMasked(), response.outputMasked(), toolCalls, events, response.model(),
                elapsedMs, policyVersion);
    }

    /**
     * 把执行面回传的类目列表拼成 {@code agent_trace.guard_categories}（varchar(512)）。
     *
     * <p>类目集合是有限枚举，当前最坏情况约 126 字符（6 类全中 + 5 个逗号），离 512 还有余量。
     * 但仍然按列宽裁剪：这条链路属于**纵深防御**——执行面将来多报几个类目、
     * 或者上游换了实现，溢出就是 MySQL 严格模式下的错误。
     * 而本方法跑在 {@code @Transactional} 的 chat() 里，一次字符串超长会让整个对话请求 500，
     * 用户完全无从理解。加上 512 的兜底之后，最坏情况退化成"类目被截断"，不影响请求本身。
     */
    private static String joinCategories(List<String> categories) {
        return categories == null || categories.isEmpty()
                ? null : clip(String.join(",", categories), GUARD_CATEGORIES_MAX_LENGTH);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 按列宽裁剪。
     *
     * <p>与 {@code SensitiveDataMasker.preview} 同风格，但**只截断、不脱敏**：
     * 这两个字段（工具名、拒绝原因）是执行面按策略产出的受控短文本，不是用户数据。
     * null 原样返回（两列都可空），空串保留为空串。
     */
    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

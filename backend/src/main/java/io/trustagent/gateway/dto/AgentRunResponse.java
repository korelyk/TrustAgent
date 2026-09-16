package io.trustagent.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Python 执行面的响应体。
 *
 * <p>关键语义：{@code status = BLOCKED} 表示被安全策略阻断，属于**预期内的正常结果**，
 * 不是系统故障。控制面据此写安全事件而不是报错。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentRunResponse(
        String traceId,
        String status,
        String guardDecision,
        List<String> guardCategories,
        String inputMasked,
        String outputMasked,
        List<ToolCall> toolCalls,
        List<GuardEvent> securityEvents,
        String model,
        long latencyMs) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(String toolName, boolean allowed, String denyReason, String argumentsMasked,
                           String resultMasked, long latencyMs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuardEvent(String eventType, String severity, String detector, String ruleId,
                             String evidenceMasked, String actionTaken) {
    }
}

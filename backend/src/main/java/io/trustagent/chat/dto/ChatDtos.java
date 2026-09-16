package io.trustagent.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record ChatRequest(
            @NotBlank(message = "prompt 不能为空")
            @Size(max = 20000, message = "prompt 过长")
            String prompt,

            @Size(max = 20000, message = "context 过长")
            String context) {
    }

    public record ToolCallView(String toolName, boolean allowed, String denyReason, String argumentsMasked,
                               String resultMasked, long latencyMs) {
    }

    public record SecurityEventView(String eventType, String severity, String detector, String ruleId,
                                    String evidenceMasked, String actionTaken) {
    }

    public record ChatResponse(String traceId, String status, String guardDecision, List<String> guardCategories,
                               String inputMasked, String outputMasked, List<ToolCallView> toolCalls,
                               List<SecurityEventView> securityEvents, String model, long elapsedMs,
                               int policyVersion) {
    }
}

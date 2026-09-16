package io.trustagent.app.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** Agent 应用相关的请求/响应 DTO 集合。放在一个文件里便于对照阅读，每个都是独立的 record。 */
public final class AppDtos {

    private AppDtos() {
    }

    public record AgentAppCreateRequest(
            @NotBlank(message = "应用编码不能为空")
            @Pattern(regexp = "^[a-z][a-z0-9-]{2,63}$",
                    message = "编码需以小写字母开头，仅含小写字母/数字/连字符，长度 3~64")
            String code,

            @NotBlank(message = "应用名称不能为空")
            @Size(max = 128)
            String name,

            @Size(max = 512)
            String description,

            @Size(max = 2000, message = "系统提示词最长 2000 字符")
            String systemPrompt) {
    }

    public record AgentAppUpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String description,
            @Size(max = 2000) String systemPrompt,
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED", message = "状态只能是 ACTIVE 或 DISABLED")
            String status) {
    }

    public record AgentAppView(Long id, String code, String name, String description, Long ownerId,
                               String ownerName, String systemPrompt, String status, Integer enabledToolCount,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record ToolConfigRequest(
            @NotBlank String toolName,
            @NotNull Boolean enabled,
            @Size(max = 4000) String paramSchema,
            @Size(max = 512) String description) {
    }

    public record ToolView(String toolName, Boolean enabled, String paramSchema, String description,
                           boolean sideEffect) {
    }

    /**
     * 安全策略提交体。
     *
     * <p>范围限制写在注解上而不是散在 Service 里，好处是校验信息可直接返回给前端，
     * 且 OpenAPI 文档能自动体现约束。
     */
    public record PolicyRequest(
            @NotNull Boolean guardEnabled,
            @NotNull @Min(value = 1, message = "最多工具调用次数至少为 1")
            @Max(value = 20, message = "最多工具调用次数不超过 20") Integer maxToolCalls,
            @NotNull @Min(value = 64, message = "最大输入长度至少 64")
            @Max(value = 20000, message = "最大输入长度不超过 20000") Integer maxInputLength,
            @NotNull @Min(value = 1000, message = "任务超时至少 1000ms")
            @Max(value = 120000, message = "任务超时不超过 120000ms") Integer taskTimeoutMs,
            @Size(max = 20, message = "域名白名单最多 20 条") List<@Size(max = 253) String> domainWhitelist,
            @Size(max = 30, message = "敏感参数规则最多 30 条") List<@Size(max = 64) String> deniedParamRules,
            @NotNull Boolean confirmSideEffects,
            @NotNull Boolean injectionDetectionEnabled,
            @NotNull Boolean piiMaskingEnabled,
            @NotNull Boolean outputGuardEnabled,
            /** 乐观锁版本；为 null 表示不做版本校验（覆盖式保存）。 */
            Integer version) {
    }

    public record PolicyView(Long appId, Integer version, Boolean guardEnabled, Integer maxToolCalls,
                             Integer maxInputLength, Integer taskTimeoutMs, List<String> domainWhitelist,
                             List<String> deniedParamRules, Boolean confirmSideEffects,
                             Boolean injectionDetectionEnabled, Boolean piiMaskingEnabled,
                             Boolean outputGuardEnabled, LocalDateTime updatedAt) {
    }
}

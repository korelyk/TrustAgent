package io.trustagent.gateway.dto;

import io.trustagent.app.AgentApp;
import io.trustagent.app.SecurityPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 调用 Python 执行面的请求体（{@code POST /v1/agent/run}）。
 *
 * <p>控制面把“策略”作为**数据**下发给执行面，而不是让执行面自己查库：
 * 这样执行面不需要数据库账号（减少一个信任边界），且策略版本可随请求一起进入 Trace。
 *
 * <p>字段命名统一用 camelCase，Python 端用 Pydantic 的 alias 生成器对齐，避免两边映射错位。
 */
public record AgentRunRequest(
        Long appId,
        String appCode,
        String systemPrompt,
        String prompt,
        String context,
        List<String> allowedTools,
        Map<String, String> toolSchemas,
        Policy policy) {

    public record Policy(
            boolean guardEnabled,
            int maxToolCalls,
            int maxInputLength,
            int taskTimeoutMs,
            List<String> domainWhitelist,
            List<String> deniedParamRules,
            boolean confirmSideEffects,
            boolean injectionDetectionEnabled,
            boolean piiMaskingEnabled,
            boolean outputGuardEnabled) {

        public static Policy from(SecurityPolicy policy) {
            return new Policy(
                    Boolean.TRUE.equals(policy.getGuardEnabled()),
                    policy.getMaxToolCalls(),
                    policy.getMaxInputLength(),
                    policy.getTaskTimeoutMs(),
                    split(policy.getDomainWhitelist()),
                    split(policy.getDeniedParamRules()),
                    Boolean.TRUE.equals(policy.getConfirmSideEffects()),
                    Boolean.TRUE.equals(policy.getInjectionDetectionEnabled()),
                    Boolean.TRUE.equals(policy.getPiiMaskingEnabled()),
                    Boolean.TRUE.equals(policy.getOutputGuardEnabled()));
        }

        private static List<String> split(String csv) {
            if (csv == null || csv.isBlank()) {
                return List.of();
            }
            return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    public static AgentRunRequest of(AgentApp app, SecurityPolicy policy, String prompt, String context,
                                     List<String> allowedTools, Map<String, String> toolSchemas) {
        return new AgentRunRequest(app.getId(), app.getCode(), app.getSystemPrompt(), prompt, context,
                allowedTools, toolSchemas, Policy.from(policy));
    }
}

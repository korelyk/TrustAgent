package io.trustagent.securityevent;

import java.util.Map;
import java.util.Set;

/**
 * 安全事件类型与默认严重级别。
 *
 * <p>这份常量表必须与 Python 执行面 {@code ai-service/app/guardrails/events.py} 保持一致：
 * 控制面按类型做统计与展示，类型漂移会导致前端图表出现"未知类型"。
 * 两侧一致性由评测的跨语言集成测试覆盖（见 docs/BENCHMARK.md）。
 */
public final class SecurityEventType {

    // 输入侧
    public static final String PROMPT_INJECTION_DIRECT = "PROMPT_INJECTION_DIRECT";
    public static final String PROMPT_INJECTION_INDIRECT = "PROMPT_INJECTION_INDIRECT";
    public static final String SYSTEM_PROMPT_LEAK_ATTEMPT = "SYSTEM_PROMPT_LEAK_ATTEMPT";
    public static final String ENCODED_INSTRUCTION = "ENCODED_INSTRUCTION";
    public static final String INPUT_TOO_LONG = "INPUT_TOO_LONG";

    // 工具侧
    public static final String TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED";
    public static final String TOOL_CALL_LIMIT_EXCEEDED = "TOOL_CALL_LIMIT_EXCEEDED";
    public static final String PARAM_SCHEMA_VIOLATION = "PARAM_SCHEMA_VIOLATION";
    public static final String DENIED_PARAM_PRESENT = "DENIED_PARAM_PRESENT";
    public static final String DOMAIN_NOT_ALLOWED = "DOMAIN_NOT_ALLOWED";
    public static final String SIDE_EFFECT_CONFIRMATION_REQUIRED = "SIDE_EFFECT_CONFIRMATION_REQUIRED";

    // 数据侧
    public static final String PII_MASKED = "PII_MASKED";
    public static final String PII_LEAK_PREVENTED = "PII_LEAK_PREVENTED";

    // 输出侧
    public static final String OUTPUT_UNSAFE_HTML = "OUTPUT_UNSAFE_HTML";
    public static final String OUTPUT_UNVERIFIED_URL = "OUTPUT_UNVERIFIED_URL";
    public static final String OUTPUT_SYSTEM_PROMPT_LEAK = "OUTPUT_SYSTEM_PROMPT_LEAK";

    // 平台侧
    //: 任务超过策略允许的耗时上限（与 Python events.py 的 TASK_TIMEOUT 同步）
    public static final String TASK_TIMEOUT = "TASK_TIMEOUT";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String METRICS_MISMATCH = "METRICS_MISMATCH";

    private static final Map<String, String> SEVERITY = Map.ofEntries(
            Map.entry(PROMPT_INJECTION_DIRECT, "HIGH"),
            Map.entry(PROMPT_INJECTION_INDIRECT, "HIGH"),
            Map.entry(SYSTEM_PROMPT_LEAK_ATTEMPT, "MEDIUM"),
            Map.entry(ENCODED_INSTRUCTION, "HIGH"),
            Map.entry(INPUT_TOO_LONG, "LOW"),
            Map.entry(TOOL_NOT_ALLOWED, "HIGH"),
            Map.entry(TOOL_CALL_LIMIT_EXCEEDED, "MEDIUM"),
            Map.entry(PARAM_SCHEMA_VIOLATION, "MEDIUM"),
            Map.entry(DENIED_PARAM_PRESENT, "HIGH"),
            Map.entry(DOMAIN_NOT_ALLOWED, "HIGH"),
            Map.entry(SIDE_EFFECT_CONFIRMATION_REQUIRED, "MEDIUM"),
            Map.entry(PII_MASKED, "LOW"),
            Map.entry(PII_LEAK_PREVENTED, "HIGH"),
            Map.entry(OUTPUT_UNSAFE_HTML, "MEDIUM"),
            Map.entry(OUTPUT_UNVERIFIED_URL, "LOW"),
            Map.entry(OUTPUT_SYSTEM_PROMPT_LEAK, "HIGH"),
            Map.entry(RATE_LIMITED, "LOW"),
            Map.entry(TASK_TIMEOUT, "MEDIUM"),
            Map.entry(METRICS_MISMATCH, "MEDIUM"));

    private SecurityEventType() {
    }

    public static String severityOf(String eventType) {
        return SEVERITY.getOrDefault(eventType, "MEDIUM");
    }

    public static Set<String> all() {
        return SEVERITY.keySet();
    }
}

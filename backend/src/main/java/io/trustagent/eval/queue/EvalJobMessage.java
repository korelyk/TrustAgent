package io.trustagent.eval.queue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 投递给评测 Worker 的一条任务消息。
 *
 * <h3>为什么消息里只有"引用"而没有"内容"</h3>
 * Redis Streams 的字段值是字符串，一条消息里塞进上百条用例会把消息体撑到几百 KB，
 * 既浪费内存，也让"重放/排查"变得不可读。
 * 因此消息只带 <b>runId + 定位信息</b>，Worker 拿到后主动回控制面拉用例与执行参数。
 * 这与 {@code docs/API.md} §7 的 {@code GET /internal/eval/datasets/{id}/cases} 是同一套设计。
 *
 * <p>字段全部是字符串：这是 Redis Streams 的协议限制（字段值只能是字节串）。
 * 转换集中在本类的 {@link #toFields()} / {@link #fromFields(Map)}，
 * 避免到处写 {@code String.valueOf}。
 */
public record EvalJobMessage(Long runId, Long datasetId, Long appId, String mode, int attempt,
                             String traceId, long enqueuedAtEpochMs) {

    public Map<String, String> toFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("runId", String.valueOf(runId));
        fields.put("datasetId", String.valueOf(datasetId));
        fields.put("appId", String.valueOf(appId));
        fields.put("mode", mode == null ? "" : mode);
        fields.put("attempt", String.valueOf(attempt));
        fields.put("traceId", traceId == null ? "" : traceId);
        fields.put("enqueuedAt", String.valueOf(enqueuedAtEpochMs));
        return fields;
    }

    /**
     * 从 Redis 字段还原消息。
     *
     * <p>容忍缺失与脏值：消息可能来自旧版本的控制面（滚动升级期间），
     * 解析失败时返回 {@code null} 由调用方决定如何处置（通常是记日志并 ACK 丢弃，
     * 因为重试一条永远解析不了的消息只会堵住队列）。
     */
    public static EvalJobMessage fromFields(Map<String, String> fields) {
        if (fields == null) {
            return null;
        }
        Long runId = parseLong(fields.get("runId"));
        Long datasetId = parseLong(fields.get("datasetId"));
        Long appId = parseLong(fields.get("appId"));
        if (runId == null || datasetId == null || appId == null) {
            return null;
        }
        return new EvalJobMessage(runId, datasetId, appId, fields.getOrDefault("mode", ""),
                (int) orDefault(parseLong(fields.get("attempt")), 1L),
                emptyToNull(fields.get("traceId")),
                orDefault(parseLong(fields.get("enqueuedAt")), 0L));
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long orDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

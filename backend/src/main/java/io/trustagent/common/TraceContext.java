package io.trustagent.common;

import org.slf4j.MDC;

/**
 * 本次请求的 Trace ID 上下文。
 *
 * <p>Trace ID 由 Java 控制面在入口处生成（或接受调用方传入），写入 MDC 与响应头，
 * 并在调用 Python 执行面时通过 {@code X-Trace-Id} 透传，从而实现跨语言串联。
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        CURRENT.set(traceId);
        MDC.put(MDC_KEY, traceId);
    }

    public static String currentTraceId() {
        String id = CURRENT.get();
        return id == null ? "unknown" : id;
    }

    /** 生成一个 32 位十六进制 Trace ID，不含连字符，便于日志检索。 */
    public static String newTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }
}

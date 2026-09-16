package io.trustagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个请求建立 Trace ID 上下文。
 *
 * <p>安全考虑：外部传入的 {@code X-Trace-Id} **不被信任**，只接受 8~64 位 {@code [A-Za-z0-9_-]}，
 * 否则丢弃并重新生成。这样可以防止日志注入（换行、控制字符）与超长值污染日志/列。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TraceContext.HEADER);
        String traceId = (incoming != null && SAFE_TRACE_ID.matcher(incoming).matches())
                ? incoming
                : TraceContext.newTraceId();
        TraceContext.set(traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}

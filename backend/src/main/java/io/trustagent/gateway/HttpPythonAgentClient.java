package io.trustagent.gateway;

import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.common.TraceContext;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.gateway.dto.AgentRunRequest;
import io.trustagent.gateway.dto.AgentRunResponse;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 通过 HTTP 调用 Python 执行面。
 *
 * <p>错误处理策略：
 * <ul>
 *   <li>连接失败 / 超时 → 502 / 504，并把原始原因写日志（含 traceId），响应里不暴露下游地址细节。</li>
 *   <li>执行面返回 4xx/5xx → 记日志（含下游响应体，便于排查），对上层返回 502。</li>
 *   <li>请求体不记录全文日志，避免把用户 Prompt 与 PII 写进日志。</li>
 * </ul>
 */
@Component
public class HttpPythonAgentClient implements PythonAgentClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPythonAgentClient.class);
    private static final String RUN_PATH = "/v1/agent/run";
    private static final String HEALTH_PATH = "/health";

    private final RestClient restClient;
    private final TrustAgentProperties props;

    public HttpPythonAgentClient(RestClient.Builder builder, TrustAgentProperties props) {
        this.props = props;
        this.restClient = builder.baseUrl(props.getAiService().getBaseUrl()).build();
    }

    @Override
    public AgentRunResponse run(AgentRunRequest request, String traceId) {
        String effectiveTraceId = traceId == null ? TraceContext.currentTraceId() : traceId;
        try {
            AgentRunResponse response = restClient.post()
                    .uri(RUN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(props.getInternal().getHeader(), props.getInternal().getToken())
                    .header(TraceContext.HEADER, effectiveTraceId)
                    .body(request)
                    .retrieve()
                    .body(AgentRunResponse.class);
            if (response == null) {
                throw new BizException(ErrorCode.AI_SERVICE_UNAVAILABLE, "执行面返回空响应体");
            }
            return response;
        } catch (ResourceAccessException ex) {
            Throwable root = rootCause(ex);
            boolean timeout = root instanceof SocketTimeoutException
                    || String.valueOf(root.getMessage()).toLowerCase().contains("timed out")
                    || String.valueOf(root.getMessage()).toLowerCase().contains("timeout");
            log.error("调用执行面失败 traceId={} timeout={} err={}", effectiveTraceId, timeout, root.getMessage());
            throw new BizException(timeout ? ErrorCode.AI_SERVICE_TIMEOUT : ErrorCode.AI_SERVICE_UNAVAILABLE,
                    timeout ? "执行面处理超时，请稍后重试"
                            : "执行面不可用，请确认 ai-service 已启动（默认 http://localhost:8000）");
        } catch (RestClientException ex) {
            log.error("执行面返回错误 traceId={} err={}", effectiveTraceId, ex.getMessage());
            throw new BizException(ErrorCode.AI_SERVICE_UNAVAILABLE, "执行面返回异常响应");
        }
    }

    @Override
    public boolean healthy() {
        try {
            restClient.get()
                    .uri(HEALTH_PATH)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.debug("执行面健康检查失败: {}", ex.getMessage());
            return false;
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** 供配置诊断与测试断言：当前配置的读取超时。 */
    public Duration readTimeout() {
        return Duration.ofMillis(props.getAiService().getReadTimeoutMs());
    }

    public String baseUrl() {
        return props.getAiService().getBaseUrl();
    }
}

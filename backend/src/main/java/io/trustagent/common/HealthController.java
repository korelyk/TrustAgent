package io.trustagent.common;

import io.trustagent.gateway.PythonAgentClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 存活探针。
 *
 * <p>刻意**不返回**版本号、构建时间、依赖版本等细节：这些信息对攻击者做版本指纹识别很有用，
 * 而探针只需要回答"进程是否还能提供服务"。
 */
@RestController
public class HealthController {

    private final PythonAgentClient pythonAgentClient;

    public HealthController(PythonAgentClient pythonAgentClient) {
        this.pythonAgentClient = pythonAgentClient;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", Instant.now().toString());
        body.put("aiServiceReachable", pythonAgentClient.healthy());
        return ApiResponse.ok(body);
    }
}

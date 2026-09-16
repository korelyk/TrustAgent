package io.trustagent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.common.TraceContext;
import io.trustagent.gateway.PythonAgentClient;
import io.trustagent.gateway.dto.AgentRunRequest;
import io.trustagent.gateway.dto.AgentRunResponse;
import io.trustagent.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 回归测试：**失败路径的 Trace 与审计必须留得下来**。
 *
 * <h3>这个测试保护的是一条真实发生过的缺陷</h3>
 * {@code ChatService.chat} 是 {@code @Transactional} 的。它在 catch 里调用
 * {@code traceService.markFailed(...)} 与 {@code auditService.record("AGENT_CHAT_FAILED", ...)}，
 * 紧接着 {@code throw ex} 让整个事务回滚——于是这两条记录**一起被回滚掉**。
 * 实测：停掉 ai-service 后发起对话，接口如实返回 502，但
 * {@code audit_log} 里查不到 {@code AGENT_CHAT_FAILED}，
 * {@code GET /api/traces/<traceId>} 直接 404（Trace 行本身也随事务回滚了）。
 *
 * <p>这类缺陷的形状很特别：接口返回了正确的错误码、日志里有 ERROR、监控也不会有异常尖刺，
 * 看起来"处理得很好"——只有去查库才会发现最该被记住的那件事**根本没留下**。
 * 所以回归测试必须断言**库里的行**，而不是断言"响应是 502"。
 *
 * <h3>为什么要断言"没有 AGENT_CHAT_EXECUTED"</h3>
 * 修好失败留痕之后，另一种退化是让失败也走成功路径的审计，把两类事实混成一种。
 * 成功路径刻意保持原样（业务成功才留下 AGENT_CHAT_EXECUTED），这条断言就是那个边界的守卫。
 *
 * <h3>为什么可以桩化执行面</h3>
 * 与 {@code SecurityEventPersistenceTest} 相同的理由：{@link PythonAgentClient} 是接口，
 * 桩化的是**跨进程边界**（控制面 → 执行面），而不是被测逻辑本身。
 * 请求校验、属主校验、限流、事务、落库、审计全部走真实代码。
 */
class ChatFailurePersistenceTest extends IntegrationTestBase {

    @TestConfiguration
    static class FailingAgentConfig {

        /**
         * 永远失败的执行面。抛 {@link BizException} 而不是随便一个 RuntimeException：
         * 被 {@code ChatService} 捕获并转成 502 的正是这一支（连接失败/超时/下游 5xx 都走这里），
         * 用别的异常类型测的就不是同一条分支了。
         */
        @Bean
        @Primary
        PythonAgentClient failingPythonAgentClient() {
            return new PythonAgentClient() {
                @Override
                public AgentRunResponse run(AgentRunRequest request, String traceId) {
                    throw new BizException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                            "执行面不可用（测试注入）");
                }

                @Override
                public boolean healthy() {
                    return false;
                }
            };
        }
    }

    private long createApp(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/apps")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of(
                                "code", uniqueAppCode("fail-app"),
                                "name", "失败留痕测试应用",
                                "description", "验证失败路径的 Trace 与审计是否落库",
                                "systemPrompt", "只使用被允许的工具。"))))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data").get("id").asLong();
    }

    @Test
    @DisplayName("执行面不可用时：Trace 落为 FAILED、审计留下 AGENT_CHAT_FAILED，且请求本身仍返回 502")
    void failurePathLeavesTraceAndAuditBehind() throws Exception {
        String token = tokenFor("chat_fail_dev", "DEVELOPER");
        long appId = createApp(token);

        MvcResult result = mockMvc.perform(post("/api/apps/" + appId + "/chat")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("prompt", "帮我查一下今天的流量"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(50201))
                .andReturn();

        // traceId 从响应头取：这是调用方实际能拿到的东西（异常响应体里没有它）。
        // 这样做也顺带验证了"失败响应同样带着可追溯的 Trace ID"。
        String traceId = result.getResponse().getHeader(TraceContext.HEADER);
        assertNotNull(traceId, "失败响应也必须带 " + TraceContext.HEADER + " 头，否则用户无法报出可查的 ID");

        // ① Trace 必须存在且是 FAILED。曾经这里是 404 —— 因为 createRunning 也在业务事务里被回滚了。
        Map<String, Object> trace = jdbcTemplate.queryForMap(
                "select status, error_message from agent_trace where trace_id = ?", traceId);
        assertEquals("FAILED", trace.get("status"), "失败请求的 Trace 必须以 FAILED 留在库里");
        assertTrue(String.valueOf(trace.get("error_message")).contains("执行面不可用"),
                "错误原因要写进 Trace，否则只有一个 FAILED 状态无法复盘："
                        + trace.get("error_message"));

        // ② 失败审计必须存在。这是"失败也要留痕"最核心的一条。
        int failedAudits = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = ? and trace_id = ?",
                Integer.class, "AGENT_CHAT_FAILED", traceId);
        assertEquals(1, failedAudits, "失败路径必须留下 AGENT_CHAT_FAILED 审计");

        // ③ 成功路径的审计**不能**出现：业务根本没成功，留下它就是在撒谎。
        int executedAudits = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = ? and trace_id = ?",
                Integer.class, "AGENT_CHAT_EXECUTED", traceId);
        assertEquals(0, executedAudits, "失败的请求不得留下 AGENT_CHAT_EXECUTED");

        // ④ 端到端：用户凭 traceId 真的能查到这条记录（接口层不再 404）
        MvcResult detail = mockMvc.perform(get("/api/traces/" + traceId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trace.status").value("FAILED"))
                .andReturn();
        JsonNode audits = json(detail).get("data").get("auditLogs");
        assertFalse(audits.isEmpty(), "Trace 详情里应当能直接看到同一条链路上的审计记录");
    }

    @Test
    @DisplayName("失败请求不产生工具调用记录：不能把没发生的事写进留痕")
    void failurePathWritesNoToolCalls() throws Exception {
        String token = tokenFor("chat_fail_dev2", "DEVELOPER");
        long appId = createApp(token);

        String traceId = mockMvc.perform(post("/api/apps/" + appId + "/chat")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("prompt", "帮我算一下 1+1"))))
                .andExpect(status().isBadGateway())
                .andReturn().getResponse().getHeader(TraceContext.HEADER);

        int toolCalls = jdbcTemplate.queryForObject(
                "select count(*) from tool_call_record where trace_id = ?", Integer.class, traceId);
        assertEquals(0, toolCalls, "执行面都没跑起来，不该有任何工具调用记录");
    }

    @Test
    @DisplayName("失败留痕与请求体内容无关：脱敏后的输入预览仍然写入 Trace")
    void inputPreviewIsStoredEvenWhenDownstreamFails() throws Exception {
        String token = tokenFor("chat_fail_dev3", "DEVELOPER");
        long appId = createApp(token);

        // 提示里带明文手机号：Trace 里必须只存脱敏后的片段
        String traceId = mockMvc.perform(post("/api/apps/" + appId + "/chat")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("prompt", "我的手机号是 13800138000，帮我查流量"))))
                .andExpect(status().isBadGateway())
                .andReturn().getResponse().getHeader(TraceContext.HEADER);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select input_preview from agent_trace where trace_id = ?", traceId);
        assertEquals(1, rows.size());
        String preview = String.valueOf(rows.get(0).get("input_preview"));
        assertFalse(preview.contains("13800138000"),
                "库里不得出现原始手机号，实际为: " + preview);
        assertTrue(preview.contains("138"), "脱敏后仍应保留可读上下文: " + preview);
    }
}

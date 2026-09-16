package io.trustagent.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.trustagent.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 审计哈希链的端到端验证（真实数据库 + 真实接口）。
 *
 * <p>对应演示场景 F：**改一条审计记录，验证接口要能定位到首个异常位置**。
 * 做法是直接改数据库（模拟"有人拿到了 DB 写权限"），验证接口必须报
 * {@code RECORD_HASH_MISMATCH} 并把 {@code firstBrokenSeq} 指到被改的那一条；
 * 断言结束后在 {@code finally} 里恢复原值，避免污染后续用例。
 *
 * <p>只证明"篡改可检测"（tamper-evident），不证明"篡改不可能"——
 * 有 DB 写权限的人可以重写整条链。这一点写在 {@code docs/SECURITY_LIMITATIONS.md}。
 *
 * <p>{@code SEQ_GAP} / {@code PREV_HASH_MISMATCH} 两种异常由
 * {@link AuditVerificationServiceTest} 用假数据确定性地覆盖，不在这里靠造库数据实现。
 */
class AuditChainIntegrationTest extends IntegrationTestBase {

    private static final String ADMIN = "audit_chain_admin";

    @Autowired
    private AuditService auditService;

    private JsonNode verify(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/audit/verify")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data");
    }

    @Test
    @DisplayName("完整链验证通过，并返回检查过的记录数")
    void intactChainVerifies() throws Exception {
        String token = tokenFor(ADMIN, "ADMIN");
        // 先确保链上至少有记录，避免依赖其他测试的执行顺序
        auditService.record("TEST_CHAIN_SEED", "test", "seed", Map.of("note", "chain seed"));

        JsonNode data = verify(token);
        assertTrue(data.get("valid").asBoolean());
        assertTrue(data.get("checkedRecords").asLong() > 0);
        // 链完整时 firstBrokenSeq / detail 为 null，会被全局的 NON_NULL 序列化策略直接省略，
        // 因此这里判断"缺失或 null"都算通过，而不是用 get(...).isNull()（那会 NPE）。
        JsonNode firstBroken = data.path("firstBrokenSeq");
        assertTrue(firstBroken.isMissingNode() || firstBroken.isNull(),
                "链完整时不应给出异常位置，实际: " + firstBroken);
        assertEquals("NONE", data.get("reason").asText());
    }

    @Test
    @DisplayName("审计日志接口按 action 过滤能查到记录（ADMIN 可读）")
    void auditLogsAreQueryable() throws Exception {
        String token = tokenFor(ADMIN, "ADMIN");
        auditService.record("TEST_CHAIN_QUERY", "test", "q1", Map.of("k", "v"));

        mockMvc.perform(get("/api/audit/logs")
                        .param("action", "TEST_CHAIN_QUERY")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("改一条审计记录内容后，验证接口定位到首个异常 seq 并给出原因")
    void tamperingIsDetectedAtFirstBrokenSeq() throws Exception {
        String token = tokenFor(ADMIN, "ADMIN");
        auditService.record("TEST_CHAIN_TAMPER_TARGET", "test", "t1", Map.of("note", "before tamper"));

        Long seq = jdbcTemplate.queryForObject("select max(seq) from audit_log", Long.class);
        assertNotNull(seq, "链上必须至少有一条记录");
        String originalDetail = jdbcTemplate.queryForObject(
                "select detail_json from audit_log where seq = ?", String.class, seq);

        try {
            jdbcTemplate.update("update audit_log set detail_json = ? where seq = ?",
                    "{\"tampered\":\"by-test\"}", seq);

            JsonNode data = verify(token);
            assertFalse(data.get("valid").asBoolean(), "内容被改后验证必须失败");
            assertEquals(seq.longValue(), data.get("firstBrokenSeq").asLong(), "必须定位到被修改的那一条");
            assertEquals("RECORD_HASH_MISMATCH", data.get("reason").asText());
            assertNotNull(data.get("detail"));
        } finally {
            jdbcTemplate.update("update audit_log set detail_json = ? where seq = ?", originalDetail, seq);
        }

        // 恢复后必须重新变成完整链：证明上面的失败确实来自"那一条被改"，而不是验证逻辑本身坏了
        assertTrue(verify(token).get("valid").asBoolean());
    }
}

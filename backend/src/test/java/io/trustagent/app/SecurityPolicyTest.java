package io.trustagent.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.trustagent.support.IntegrationTestBase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 安全策略的保存、版本乐观锁与参数校验，以及“策略变更必须留痕”。
 */
class SecurityPolicyTest extends IntegrationTestBase {

    private static final String DEV = "policy_dev";

    private static Map<String, Object> policyBody(Integer version, List<String> domainWhitelist) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("guardEnabled", true);
        body.put("maxToolCalls", 5);
        body.put("maxInputLength", 2000);
        body.put("taskTimeoutMs", 10000);
        body.put("domainWhitelist", domainWhitelist);
        body.put("deniedParamRules", List.of("password", "token"));
        body.put("confirmSideEffects", true);
        body.put("injectionDetectionEnabled", true);
        body.put("piiMaskingEnabled", true);
        body.put("outputGuardEnabled", true);
        body.put("version", version);
        return body;
    }

    private long createApp(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/apps")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of(
                                "code", uniqueAppCode("policy-app"),
                                "name", "策略测试应用",
                                "description", "验证策略保存与乐观锁",
                                "systemPrompt", "只使用被允许的工具。"))))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data").get("id").asLong();
    }

    private JsonNode getPolicy(String token, long appId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/apps/" + appId + "/policy")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data");
    }

    @Test
    @DisplayName("新建应用会落一份 secure-by-default 的默认策略（版本 1）")
    void creatingAppSeedsDefaultPolicy() throws Exception {
        String token = tokenFor(DEV, "DEVELOPER");
        long appId = createApp(token);

        JsonNode policy = getPolicy(token, appId);
        assertTrue(policy.get("version").asInt() >= 1, "策略必须有初始版本号");
        assertTrue(policy.get("guardEnabled").asBoolean());
        assertTrue(policy.get("maxToolCalls").asInt() > 0);
        assertTrue(policy.get("maxInputLength").asInt() >= 64);
        assertTrue(policy.get("domainWhitelist").toString().contains("docs.trustagent.local"));
        assertTrue(policy.get("piiMaskingEnabled").asBoolean());
    }

    @Test
    @DisplayName("保存策略使版本自增，并且写入审计（不可静默改动）")
    void savingPolicyIncrementsVersionAndWritesAudit() throws Exception {
        String devToken = tokenFor(DEV, "DEVELOPER");
        String adminToken = tokenFor("policy_admin", "ADMIN");
        long appId = createApp(devToken);

        int before = getPolicy(devToken, appId).get("version").asInt();

        MvcResult updated = mockMvc.perform(put("/api/apps/" + appId + "/policy")
                        .header("Authorization", bearer(devToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(policyBody(before, List.of("docs.trustagent.local")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        JsonNode data = json(updated).get("data");
        assertTrue(data.get("version").asInt() == before + 1, "版本必须自增");
        assertTrue(data.get("maxToolCalls").asInt() == 5);

        // 审计里必须能查到这次变更，且带上变更前后的快照
        MvcResult audit = mockMvc.perform(get("/api/audit/logs")
                        .param("action", "SECURITY_POLICY_UPDATED")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = json(audit).get("data");
        assertNotNull(page);
        assertTrue(page.get("total").asLong() >= 1, "策略变更必须留下审计记录");

        // 注意：detailJson 在响应里是一个"被转义的字符串"，直接对整串做 contains("\"version\"")
        // 会因为引号被再转义一层而永远不成立。必须先 asText() 还原成原始 JSON 文本再判断。
        boolean hasBeforeAfterSnapshot = false;
        for (JsonNode record : page.path("records")) {
            String detail = record.path("detailJson").asText("");
            if (detail.contains("\"before\"") && detail.contains("\"after\"")) {
                hasBeforeAfterSnapshot = true;
                break;
            }
        }
        assertTrue(hasBeforeAfterSnapshot, "审计明细必须同时保留变更前与变更后的策略快照");
    }

    @Test
    @DisplayName("版本不一致返回 409（乐观锁），防止并发覆盖")
    void staleVersionIsRejectedWithConflict() throws Exception {
        String token = tokenFor(DEV, "DEVELOPER");
        long appId = createApp(token);
        int staleVersion = getPolicy(token, appId).get("version").asInt();

        mockMvc.perform(put("/api/apps/" + appId + "/policy")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(policyBody(staleVersion, List.of("docs.trustagent.local")))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/apps/" + appId + "/policy")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(policyBody(staleVersion, List.of("docs.trustagent.local")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("非法域名与非法敏感参数名在入库前被拒（400），不会写进策略表")
    void invalidPolicyValuesAreRejected() throws Exception {
        String token = tokenFor(DEV, "DEVELOPER");
        long appId = createApp(token);

        mockMvc.perform(put("/api/apps/" + appId + "/policy")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(policyBody(null, List.of("not a domain")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        Map<String, Object> badParam = policyBody(null, List.of("docs.trustagent.local"));
        badParam.put("deniedParamRules", List.of("drop table;"));
        mockMvc.perform(put("/api/apps/" + appId + "/policy")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(badParam)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }
}

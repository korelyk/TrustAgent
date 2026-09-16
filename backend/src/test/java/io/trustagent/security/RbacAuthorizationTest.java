package io.trustagent.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trustagent.support.IntegrationTestBase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * RBAC 授权与越权拒绝（正常授权 + 越权各覆盖一遍）。
 *
 * <h3>为什么 403 用例必须带合法请求体</h3>
 * {@code @Valid @RequestBody} 的参数解析发生在方法调用**之前**，而 {@code @PreAuthorize}
 * 是在方法调用时生效的。所以如果给越权用例塞一个非法请求体，得到的会是 400 而不是 403——
 * 那样测的其实是"参数校验"，不是"越权拦截"。这里每个 403 用例都刻意构造了合法请求体。
 */
class RbacAuthorizationTest extends IntegrationTestBase {

    private static final String ADMIN = "rbac_admin";
    private static final String DEV_A = "rbac_dev_a";
    private static final String DEV_B = "rbac_dev_b";
    private static final String AUDITOR = "rbac_auditor";

    private static Map<String, Object> validCreateUserBody(String username) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", "Valid@12345");
        body.put("displayName", username);
        body.put("email", username + "@test.local");
        body.put("roles", List.of("DEVELOPER"));
        return body;
    }

    private static Map<String, Object> validPolicyBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("guardEnabled", true);
        body.put("maxToolCalls", 3);
        body.put("maxInputLength", 4000);
        body.put("taskTimeoutMs", 15000);
        body.put("domainWhitelist", List.of("docs.trustagent.local"));
        body.put("deniedParamRules", List.of("password"));
        body.put("confirmSideEffects", true);
        body.put("injectionDetectionEnabled", true);
        body.put("piiMaskingEnabled", true);
        body.put("outputGuardEnabled", true);
        return body;
    }

    /** 用指定 Token 建一个应用，返回 appId。 */
    private long createApp(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/apps")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of(
                                "code", uniqueAppCode("rbac-app"),
                                "name", "RBAC 测试应用",
                                "description", "用于验证角色与属主边界",
                                "systemPrompt", "只使用被允许的工具。"))))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("data").get("id").asLong();
    }

    @Test
    @DisplayName("用户管理接口只有 ADMIN 能访问，DEVELOPER/AUDITOR 一律 403")
    void userAdminApiIsAdminOnly() throws Exception {
        String devToken = tokenFor(DEV_A, "DEVELOPER");
        String auditorToken = tokenFor(AUDITOR, "AUDITOR");
        String adminToken = tokenFor(ADMIN, "ADMIN");

        mockMvc.perform(get("/api/users").header("Authorization", bearer(devToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(auditorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(validCreateUserBody("rbac_should_not_exist"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        mockMvc.perform(get("/api/users").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("AUDITOR 只读：能看应用与审计，但不能改策略")
    void auditorIsReadOnly() throws Exception {
        String devToken = tokenFor(DEV_A, "DEVELOPER");
        String auditorToken = tokenFor(AUDITOR, "AUDITOR");
        long appId = createApp(devToken);

        mockMvc.perform(get("/api/apps").header("Authorization", bearer(auditorToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/apps/" + appId + "/policy").header("Authorization", bearer(auditorToken)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/apps/" + appId + "/policy")
                        .header("Authorization", bearer(auditorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(validPolicyBody())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        mockMvc.perform(get("/api/audit/logs").header("Authorization", bearer(auditorToken)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("版本冲突（409）之前先做越权判断：DEVELOPER 不能改别人的策略")
    void developerCannotModifyAnotherDevelopersApp() throws Exception {
        String tokenA = tokenFor(DEV_A, "DEVELOPER");
        String tokenB = tokenFor(DEV_B, "DEVELOPER");
        long appIdOfA = createApp(tokenA);

        // 读别人的应用：403（属主级校验，不是权限点级的）
        mockMvc.perform(get("/api/apps/" + appIdOfA).header("Authorization", bearer(tokenB)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        // 改别人的策略：同样是 403
        mockMvc.perform(put("/api/apps/" + appIdOfA + "/policy")
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(validPolicyBody())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        // 自己的应用可以读：证明上面的 403 来自属主校验，而不是 Token 本身有问题
        mockMvc.perform(get("/api/apps/" + appIdOfA).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("审计读取与验证权限只给 ADMIN/AUDITOR，DEVELOPER 拿不到")
    void auditApisRequireAuditPermissions() throws Exception {
        String devToken = tokenFor(DEV_A, "DEVELOPER");
        String adminToken = tokenFor(ADMIN, "ADMIN");

        mockMvc.perform(get("/api/audit/logs").header("Authorization", bearer(devToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit/verify").header("Authorization", bearer(devToken)))
                .andExpect(status().isForbidden());

        // 只断言"能调到"，链内容的正确性由 AuditChainIntegrationTest 专门覆盖，
        // 避免这个用例因为别的测试刚改过审计表而误红
        mockMvc.perform(get("/api/audit/verify").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}

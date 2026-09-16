package io.trustagent.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.ratelimit.InMemorySlidingWindowRateLimiter;
import io.trustagent.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 登录、JWT 校验与登录限流。
 *
 * <p>覆盖 {@code docs/ROADMAP.md} 阶段的 Java 测试要求中的“登录和 JWT 校验”“限流”两项。
 */
class AuthSecurityTest extends IntegrationTestBase {

    /** 固定用户名，避免与其他测试类的限流桶互相影响。 */
    private static final String USERNAME = "auth_probe_dev";

    @Autowired
    private TrustAgentProperties props;

    /** 测试 profile 下必然存在内存限流器；置为可空是为了不把测试写死在某个实现上。 */
    @Autowired(required = false)
    private InMemorySlidingWindowRateLimiter inMemoryRateLimiter;

    private static final String WRONG_PASSWORD = "WrongPass@12345";

    @Test
    @DisplayName("登录成功返回 JWT、有效期与角色权限")
    void loginReturnsTokenAndIdentity() throws Exception {
        ensureUser(USERNAME, "DEVELOPER");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("username", USERNAME, "password", TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        JsonNode data = json(result).get("data");
        assertFalse(data.get("token").asText().isBlank(), "必须返回 JWT");
        assertEquals("Bearer", data.get("tokenType").asText());
        assertTrue(data.get("expiresInSeconds").asLong() > 0);
        assertEquals(USERNAME, data.get("user").get("username").asText());
        assertTrue(data.get("user").get("roles").toString().contains("DEVELOPER"));
        assertTrue(data.get("user").get("permissions").toString().contains("agent:chat"));
    }

    @Test
    @DisplayName("密码错误与用户不存在返回同一个错误码，避免用户名枚举")
    void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
        ensureUser(USERNAME, "DEVELOPER");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("username", USERNAME, "password", WRONG_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("username", "no_such_user_9f3a", "password", WRONG_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("空用户名/空密码在进入业务逻辑前就被参数校验拒绝")
    void blankCredentialsAreRejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("username", " ", "password", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("无 Token 与非法 Token 访问受保护接口都返回 401")
    void protectedEndpointRejectsMissingOrInvalidToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());

        // 用 Internal Token 冒充用户 JWT 也必须失败：两条凭证链路完全隔离
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(INTERNAL_TOKEN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("合法 JWT 能读到当前用户信息")
    void validTokenReadsCurrentUser() throws Exception {
        String token = tokenFor(USERNAME, "DEVELOPER");

        MvcResult result = mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        assertEquals(USERNAME, json(result).get("data").get("username").asText());
    }

    @Test
    @DisplayName("登录限流：第 limit 次之后被拒，返回 429 与 Retry-After")
    void repeatedFailedLoginsAreRateLimited() throws Exception {
        String username = "auth_rl_probe";
        // 清空窗口，让本用例不依赖其他用例是否已经打过同一个桶
        if (inMemoryRateLimiter != null) {
            inMemoryRateLimiter.reset();
        }
        int limit = props.getRateLimit().getLogin().getLimit();
        assertTrue(limit > 0, "测试 profile 必须开启登录限流，否则这个用例没有意义");

        for (int i = 0; i < limit; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("username", username, "password", WRONG_PASSWORD))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("username", username, "password", WRONG_PASSWORD))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value(42901));

        // 限流键是“用户名 + 来源 IP”的组合：换一个用户名不应该被同一个桶连坐
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("username", "auth_rl_probe_other", "password", WRONG_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("伪造 X-Forwarded-For 不能绕过登录限流：换头不代表换桶")
    void forgedForwardedForCannotBypassLoginRateLimit() throws Exception {
        String username = "auth_rl_xff_probe";
        if (inMemoryRateLimiter != null) {
            inMemoryRateLimiter.reset();
        }
        int limit = props.getRateLimit().getLogin().getLimit();
        assertTrue(limit > 0, "测试 profile 必须开启登录限流，否则这个用例没有意义");

        // 这条断言是**默认值的守卫**：信任 XFF 必须由部署者显式打开。
        // 如果哪天有人把默认值改成 true，这个用例会立刻红——那就是在无声地削弱限流，
        // 而不是一次"看起来更方便"的配置调整。
        assertFalse(props.getSecurity().isTrustForwardedFor(),
                "trust-forwarded-for 必须默认关闭：XFF 是请求头，任何人都能写");

        // 关键手法：**每次换一个伪造的 XFF**。
        // 旧实现的限流维度是"用户名 + XFF 第一段"，等于让调用方自己决定用哪个桶：
        // 实测这样连打 30 次失败登录，一次 429 都不会出现——限流配置在、但完全失效。
        for (int i = 0; i < limit; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", "203.0.113." + (i + 1))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(Map.of("username", username, "password", WRONG_PASSWORD))))
                    .andExpect(status().isUnauthorized());
        }

        // 第 limit + 1 次仍然换了个没出现过的 XFF，却必须被拦住：
        // 说明限流维度来自 TCP 对端地址（MockMvc 下是 127.0.0.1），而不是请求头。
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.250")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("username", username, "password", WRONG_PASSWORD))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));
    }
}

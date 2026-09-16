package io.trustagent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.trustagent.security.JwtService;
import io.trustagent.user.SysUser;
import io.trustagent.user.UserAccountService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 集成测试基类：真实 Spring 上下文 + H2 内存库 + 内存限流/队列，不依赖 MySQL、Redis 或 Python 执行面。
 *
 * <h3>为什么夹具用 {@link JwtService} 直接签发 Token，而不是都走登录接口</h3>
 * 登录接口有 10 次/分钟的限流（见 {@code application-test.yml}）。如果每个测试都靠调登录来拿 Token，
 * 一次 `mvn test` 跑下来很容易把额度耗尽，表现为“莫名其妙的 429”。
 * 因此：**夹具用直接签发**（快且不占额度），登录接口本身由 {@code AuthSecurityTest} 专门覆盖，
 * 限流也在那里用独立用户名验证，避免跨测试相互影响。
 *
 * <p>同一个 JVM 内所有测试共享一个 Spring 上下文与一份内存库（{@code DB_CLOSE_DELAY=-1}），
 * 因此测试之间必须靠“唯一用户名 / 唯一应用编码”隔离，不能依赖执行顺序或相互清库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    /** 测试账号统一口令，仅用于本地 H2，不涉及任何真实环境。 */
    protected static final String TEST_PASSWORD = "Test@12345";

    /** 与 {@code src/test/resources/application-test.yml} 中的固定值保持一致（非真实凭证）。 */
    protected static final String INTERNAL_HEADER = "X-Internal-Token";
    protected static final String INTERNAL_TOKEN = "test-only-internal-token-0123456789";

    /** MockMvc 的默认远端地址，登录限流键里会用到它。 */
    protected static final String TEST_CLIENT_IP = "127.0.0.1";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected UserAccountService userAccountService;

    @Autowired
    protected JwtService jwtService;

    /** 幂等创建测试用户（已存在则复用），返回用户实体。 */
    protected SysUser ensureUser(String username, String... roles) {
        SysUser existing = userAccountService.findByUsername(username);
        if (existing != null) {
            return existing;
        }
        return userAccountService.createUser(username, TEST_PASSWORD, username,
                username + "@test.local", List.of(roles));
    }

    /** 直接签发一个可用 Token（权限每次回库查，因此这里只需要用户与角色存在）。 */
    protected String tokenFor(String username, String... roles) {
        SysUser user = ensureUser(username, roles);
        return jwtService.issue(user, userAccountService.roleCodes(user.getId()));
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    /** 生成一个合法且唯一的应用编码（满足 {@code ^[a-z][a-z0-9-]{2,63}$}）。 */
    protected static String uniqueAppCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}

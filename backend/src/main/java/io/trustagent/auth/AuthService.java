package io.trustagent.auth;

import io.trustagent.audit.AuditService;
import io.trustagent.auth.dto.LoginRequest;
import io.trustagent.auth.dto.LoginResponse;
import io.trustagent.auth.dto.UserInfoView;
import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.ratelimit.RateLimitService;
import io.trustagent.security.JwtService;
import io.trustagent.security.UserPrincipal;
import io.trustagent.user.SysUser;
import io.trustagent.user.UserAccountService;
import io.trustagent.user.UserStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountService userAccountService;
    private final JwtService jwtService;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final TrustAgentProperties props;

    /**
     * 登录。
     *
     * <p>限流维度用“用户名 + 客户端 IP”的组合：
     * 只按 IP 会被分布式撞库绕过；只按用户名会让攻击者用大量用户名把某个 IP 拖黑（误伤 NAT 后的正常用户）。
     * 组合键同时限制“单 IP 猜多个账号”和“多 IP 猜同一个账号”。
     */
    public LoginResponse login(LoginRequest request, String clientIp) {
        String identity = request.username() + "|" + (clientIp == null ? "unknown" : clientIp);
        rateLimitService.enforce("login", identity, rateLimitService.loginWindow());

        SysUser user = userAccountService.findByUsername(request.username());
        if (user == null || !userAccountService.verifyPassword(user, request.password())) {
            auditService.record("AUTH_LOGIN_FAILED", "user", null,
                    Map.of("username", request.username(), "reason", "BAD_CREDENTIALS"));
            // 统一提示，避免通过错误信息区分“用户不存在”和“密码错误”来做用户名枚举
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!UserStatus.ACTIVE.equals(user.getStatus())) {
            auditService.record("AUTH_LOGIN_BLOCKED", "user", String.valueOf(user.getId()),
                    Map.of("username", user.getUsername(), "reason", "ACCOUNT_DISABLED"));
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        List<String> roles = userAccountService.roleCodes(user.getId());
        String token = jwtService.issue(user, roles);
        userAccountService.touchLastLogin(user.getId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("username", user.getUsername());
        detail.put("roles", roles);
        auditService.record("AUTH_LOGIN_SUCCESS", "user", String.valueOf(user.getId()), detail);
        log.info("登录成功 user={} roles={}", user.getUsername(), roles);

        UserInfoView view = new UserInfoView(user.getId(), user.getUsername(), user.getDisplayName(),
                roles, userAccountService.permissionCodes(user.getId()));
        return LoginResponse.bearer(token, jwtService.ttlSeconds(), view);
    }

    public UserInfoView currentUserInfo(UserPrincipal principal) {
        return new UserInfoView(principal.getId(), principal.getUsername(), principal.getDisplayName(),
                principal.getRoles(), principal.getPermissions());
    }

    /** 供测试与诊断使用：当前生效的限流配置。 */
    public TrustAgentProperties.Window loginWindow() {
        return props.getRateLimit().getLogin();
    }
}

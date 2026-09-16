package io.trustagent.security;

import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 读取当前登录主体。业务代码只通过这些方法拿“我是谁”，不直接接触 SecurityContextHolder。 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<UserPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    public static UserPrincipal requirePrincipal() {
        return currentPrincipal().orElseThrow(
                () -> new BizException(ErrorCode.TOKEN_INVALID, "未认证的请求"));
    }

    public static Long currentUserId() {
        return requirePrincipal().getId();
    }

    public static boolean isAdmin() {
        return currentPrincipal().map(UserPrincipal::isAdmin).orElse(false);
    }

    /** 是否放行访问某个归属资源：ADMIN 全放行，其他角色必须是 owner。 */
    public static boolean canAccessOwned(Long ownerId) {
        UserPrincipal principal = requirePrincipal();
        return principal.isAdmin() || principal.getId().equals(ownerId);
    }

    /** 越权时统一抛 403，而不是返回空数据，避免“看不见等于不存在”的迷惑行为。 */
    public static void requireOwnerOrAdmin(Long ownerId, String resourceDescription) {
        if (!canAccessOwned(ownerId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "无权访问该" + resourceDescription + "（仅属主或 ADMIN 可操作）");
        }
    }
}

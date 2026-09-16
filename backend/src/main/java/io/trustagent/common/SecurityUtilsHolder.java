package io.trustagent.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 不直接依赖 Spring Security 用户模型的只读查询工具，供异常处理器打印“谁在越权”用。
 *
 * <p>拆成独立类是为了避免 {@code common} 包反向依赖 {@code security} 包造成循环依赖。
 */
final class SecurityUtilsHolder {

    private SecurityUtilsHolder() {
    }

    static String usernameOrAnonymous() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "anonymous";
        }
        return auth.getName();
    }
}

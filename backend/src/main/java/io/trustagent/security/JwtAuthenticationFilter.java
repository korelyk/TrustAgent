package io.trustagent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.trustagent.common.ErrorCode;
import io.trustagent.user.SysUser;
import io.trustagent.user.UserAccountService;
import io.trustagent.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 解析 {@code Authorization: Bearer <jwt>} 并建立安全上下文。
 *
 * <p>设计选择：Token 里只带 uid，**权限每次都回数据库查**（{@link UserAccountService#buildPrincipal}）。
 * 好处是管理员改动角色后立即生效，不需要等 Token 过期；代价是每个请求多两次轻量查询（走唯一索引与关联表，
 * 且用户量级很小）。如果用“权限写进 Token”的方案，可以让 Token 更自包含，但会产生权限吊销延迟，
 * 这里选择“更安全但多一次查询”。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 认证失败原因通过请求属性传递给 EntryPoint，用于返回精确的错误码。 */
    public static final String AUTH_ERROR_ATTRIBUTE = "trustagent.authError";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserAccountService userAccountService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = jwtService.parse(token);
            Long userId = claims.get("uid", Number.class) == null ? null : claims.get("uid", Number.class).longValue();
            if (userId == null) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.TOKEN_INVALID);
                chain.doFilter(request, response);
                return;
            }
            SysUser user = userAccountService.findById(userId);
            if (user == null) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.TOKEN_INVALID);
                chain.doFilter(request, response);
                return;
            }
            if (!UserStatus.ACTIVE.equals(user.getStatus())) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.ACCOUNT_DISABLED);
                chain.doFilter(request, response);
                return;
            }
            List<String> roles = userAccountService.roleCodes(userId);
            UserPrincipal principal = userAccountService.buildPrincipal(user);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("JWT 认证通过 user={} roles={}", user.getUsername(), roles);
        } catch (JwtException | IllegalArgumentException ex) {
            // 不记录 Token 内容，避免日志泄露凭证
            log.debug("JWT 校验失败: {}", ex.getClass().getSimpleName());
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.TOKEN_INVALID);
        }
        chain.doFilter(request, response);
    }
}

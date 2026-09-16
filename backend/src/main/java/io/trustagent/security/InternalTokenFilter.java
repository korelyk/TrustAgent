package io.trustagent.security;

import io.trustagent.config.TrustAgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部服务凭证校验：只作用于 {@code /internal/**}。
 *
 * <p>安全要点：
 * <ol>
 *   <li>**独立凭证**：内部 Token 与用户 JWT 走不同的校验逻辑，用户 JWT 无法访问内部接口，反之亦然。</li>
 *   <li>**常量时间比较**：用 {@link MessageDigest#isEqual} 避免按字节短路比较带来的时序侧信道。</li>
 *   <li>**fail closed**：未配置内部 Token 时，内部接口一律拒绝，而不是“默认放行”。</li>
 *   <li>放行时授予 {@code INTERNAL_SERVICE} authority，配合 SecurityConfig 的
 *       {@code hasAuthority("INTERNAL_SERVICE")} 形成双重保险：即使过滤器被误删，接口仍然返回 401。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenFilter.class);
    public static final String AUTHORITY = "INTERNAL_SERVICE";
    private static final String PRINCIPAL = "internal-service";

    private final TrustAgentProperties props;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getRequestURI().startsWith("/internal/")
                || "/internal".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String configured = props.getInternal().getToken();
        String provided = request.getHeader(props.getInternal().getHeader());

        if (configured == null || configured.isBlank()) {
            log.warn("未配置 {}，内部接口已被拒绝: path={}",
                    "trustagent.internal.token", request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }
        if (provided != null && constantTimeEquals(configured, provided)) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    PRINCIPAL, null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } else {
            log.warn("内部凭证校验失败 path={} remote={}", request.getRequestURI(), request.getRemoteAddr());
        }
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}

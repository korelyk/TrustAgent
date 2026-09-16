package io.trustagent.config;

import io.trustagent.security.JsonAuthHandlers;
import io.trustagent.security.InternalTokenFilter;
import io.trustagent.security.JwtAuthenticationFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final TrustAgentProperties props;

    @Value("${trustagent.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private List<String> allowedOrigins;

    public SecurityConfig(TrustAgentProperties props) {
        this.props = props;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // cost=10：本地演示与测试的合理折中；真实生产可提升到 12 并按硬件评估登录吞吐
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   InternalTokenFilter internalTokenFilter,
                                                   JsonAuthHandlers.JsonAuthenticationEntryPoint entryPoint,
                                                   JsonAuthHandlers.JsonAccessDeniedHandler deniedHandler)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll();
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    // 只放行应用自己的 /health。
                    //
                    // 这里曾经同时放行 /actuator/health，但 pom 里**没有**引入 spring-boot-starter-actuator：
                    // 那条放行规则从来没生效过，属于"看起来配了其实不存在"的规则。
                    // 两种收尾方式里选了"删规则"而不是"加依赖"，理由是影响面更小：
                    //   * 删规则：零行为变化（当前没有任何 /actuator 端点），也不引入新的依赖与暴露面；
                    //   * 加依赖：会带进一整套管理端点，将来若有人误开 management.endpoints.web.exposure
                    //     就可能把 env/beans/heapdump 这类信息暴露出去，而本项目并不需要它们。
                    // 真要用 actuator 时再一起加依赖 + 显式收窄暴露端点，可见性更好。
                    auth.requestMatchers("/health").permitAll();
                    if (props.getSecurity().isExposeH2Console()) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    // /internal/** 不接受用户 JWT，必须持有 InternalTokenFilter 授予的 authority
                    auth.requestMatchers("/internal/**").hasAuthority(InternalTokenFilter.AUTHORITY);
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(internalTokenFilter, JwtAuthenticationFilter.class);

        if (props.getSecurity().isExposeH2Console()) {
            // 仅在显式开启（仅 dev）时放宽，避免全局关闭点击劫持防护
            http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        }
        return http.build();
    }

    /**
     * 允许前端开发服务器跨域访问。生产由 nginx 同源反代，不需要额外的 CORS 放行。
     * 只允许明确的源，不使用 {@code *}（否则浏览器会拒绝携带凭据的请求，也放大了风险面）。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Trace-Id"));
        config.setExposedHeaders(List.of("X-Trace-Id", "Retry-After"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

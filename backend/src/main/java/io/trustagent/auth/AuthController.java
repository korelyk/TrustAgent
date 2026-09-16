package io.trustagent.auth;

import io.trustagent.auth.dto.LoginRequest;
import io.trustagent.auth.dto.LoginResponse;
import io.trustagent.auth.dto.UserInfoView;
import io.trustagent.common.ApiResponse;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TrustAgentProperties props;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest servletRequest) {
        return ApiResponse.ok(authService.login(request, clientIp(servletRequest)));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfoView> me() {
        return ApiResponse.ok(authService.currentUserInfo(SecurityUtils.requirePrincipal()));
    }

    /**
     * 取客户端 IP，供登录限流做维度。
     *
     * <h3>为什么默认**不**信任 X-Forwarded-For</h3>
     * XFF 只是一个请求头，谁都能写。原来无条件取它的**第一段**，等于把限流维度的控制权
     * 交给了调用方：攻击者对同一账号每次换一个假的 XFF，就是每次换一个限流桶，
     * 连打几十次登录失败一次 429 都不会出现——限流看起来"配置了"，实际上完全失效。
     * 所以默认关闭（{@code trustagent.security.trust-forwarded-for=false}），
     * 直接用 {@code getRemoteAddr()}：那是 TCP 连接的对端地址，客户端伪造不了。
     *
     * <h3>开启时为什么取**最后一段**而不是第一段</h3>
     * 只有在"所有外部流量都必然经过自己可控的反向代理"时才可以打开这个开关。
     * 此时 XFF 的形状是 {@code <客户端自带值>, <代理观察到的对端地址>, ...}：
     * 越靠右的值由越靠近本机的可信组件写入，越靠左越可能是客户端自己编的。
     * 本项目的代理 Caddy（{@code deploy/Caddyfile.*}）对 XFF 是**追加**语义
     * （nginx 的 {@code $proxy_add_x_forwarded_for} 同理），因此最后一段就是真正的客户端 IP；
     * 无论客户端在开头塞多少假地址，都不会影响这一段的取值。取第一段则恰恰相反：
     * 取的正是攻击者最容易控制的那一段。
     *
     * <p>失败方向也是刻意选的：若将来在 Caddy 前面再加一层代理而忘了调整，
     * 最后一段会变成"上一跳代理的地址"，结果是所有用户被算进同一个桶（**限得更严**），
     * 而不是限流失效。限流这种兜底能力宁可误伤，也不要静默失效。
     *
     * <p>安全含义也写在 {@code docs/SECURITY_LIMITATIONS.md} §5 与 application.yml 的注释里。
     */
    private String clientIp(HttpServletRequest request) {
        if (props.getSecurity().isTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            String candidate = lastForwardedHop(forwarded);
            if (candidate != null) {
                return candidate;
            }
            // 开启开关但头缺失/内容不可信：退回 TCP 对端地址，绝不返回一个空的限流维度
            // （空维度会让所有这类请求共用一个桶，等于给攻击者一个"把所有人一起限掉"的手段）
        }
        return request.getRemoteAddr();
    }

    /** 取 XFF 最后一段；空白或长度异常时返回 null 交给调用方兜底。 */
    private static String lastForwardedHop(String forwarded) {
        if (forwarded == null || forwarded.isBlank()) {
            return null;
        }
        int comma = forwarded.lastIndexOf(',');
        String candidate = (comma >= 0 ? forwarded.substring(comma + 1) : forwarded).trim();
        // 只做格式兜底（列宽 45 足够放 IPv6），内容是否真的是 IP 由下游使用方式决定：
        // 这里的值只会作为**限流键的一部分**与审计明细，不会被当作地址去连接任何东西。
        return candidate.isEmpty() || candidate.length() > 45 ? null : candidate;
    }
}

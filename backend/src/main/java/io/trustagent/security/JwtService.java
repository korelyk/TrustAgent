package io.trustagent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.trustagent.config.TrustAgentProperties;
import io.trustagent.user.SysUser;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验（HS256）。
 *
 * <p>安全决策：
 * <ol>
 *   <li>密钥长度不足 32 字节直接 **启动失败** —— 弱密钥的 HS256 可被离线爆破。</li>
 *   <li>生产未配置密钥时启动失败（fail fast），不允许“默默用默认密钥”。</li>
 *   <li>仅 dev/test 允许在密钥缺失时生成进程级随机密钥并打印告警（重启即失效，符合本地开发预期）。</li>
 *   <li>Token 里只放 uid/username/roles，权限每次都从数据库读取，因此收回权限立即生效。</li>
 * </ol>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final String issuer;
    private final long ttlSeconds;

    public JwtService(TrustAgentProperties props, Environment environment) {
        String secret = props.getJwt().getSecret();
        boolean relaxedProfile = isRelaxedProfile(environment);
        if (secret == null || secret.isBlank()) {
            if (!relaxedProfile) {
                throw new IllegalStateException(
                        "缺少环境变量 TRUSTAGENT_JWT_SECRET。拒绝以未配置密钥的方式启动生产实例。"
                                + "生成方式示例: openssl rand -base64 48");
            }
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random);
            log.warn("TRUSTAGENT_JWT_SECRET 未配置，已生成进程级随机密钥（仅限 dev/test）。"
                    + "重启后旧 Token 全部失效。");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("TRUSTAGENT_JWT_SECRET 长度不足 32 字节，HS256 弱密钥存在被爆破风险");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = props.getJwt().getIssuer();
        this.ttlSeconds = props.getJwt().getTtlSeconds();
    }

    private static boolean isRelaxedProfile(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("dev".equals(profile) || "test".equals(profile) || "local".equals(profile)) {
                return true;
            }
        }
        return false;
    }

    public String issue(SysUser user, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 校验签名与签发者，返回 claims。
     *
     * @throws JwtException 签名错误、过期、格式非法
     */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}

package io.trustagent.ratelimit;

import io.trustagent.config.TrustAgentProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 限流的统一入口：负责拼装限流键、读取配置、把“被拒”翻译成异常。
 *
 * <p>键的设计：{@code <scope>:<identity>}。identity 在调用方决定——聊天用“用户+应用”，
 * 登录用“用户名+来源 IP”。用组合键而不是单一用户维度，是因为同一个人开多个应用并发打满
 * 也不应该把整个平台拖慢。
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RateLimiter rateLimiter;
    private final TrustAgentProperties props;

    /** 只检查不抛异常，返回决策（用于需要自定义提示的场景与单元测试）。 */
    public RateLimiter.Decision check(String scope, String identity, TrustAgentProperties.Window window) {
        if (!props.getRateLimit().isEnabled()) {
            return RateLimiter.Decision.allow(Integer.MAX_VALUE, window.getLimit());
        }
        return rateLimiter.acquire(scope + ":" + identity, window.getLimit(), window.getWindowSeconds());
    }

    /** 检查并在超限时抛 {@link RateLimitExceededException}（HTTP 429 + Retry-After）。 */
    public RateLimiter.Decision enforce(String scope, String identity, TrustAgentProperties.Window window) {
        RateLimiter.Decision decision = check(scope, identity, window);
        if (!decision.allowed()) {
            log.warn("限流触发 scope={} identity={} limit={}/{}s retryAfter={}s",
                    scope, identity, window.getLimit(), window.getWindowSeconds(), decision.retryAfterSeconds());
            throw new RateLimitExceededException(scope, decision.retryAfterSeconds());
        }
        return decision;
    }

    public TrustAgentProperties.Window chatWindow() {
        return props.getRateLimit().getChat();
    }

    public TrustAgentProperties.Window loginWindow() {
        return props.getRateLimit().getLogin();
    }

    public TrustAgentProperties.Window evalRunWindow() {
        return props.getRateLimit().getEvalRun();
    }
}

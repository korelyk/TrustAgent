package io.trustagent.ratelimit;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 进程内滑动窗口限流，语义与 Redis 版一致，用于：
 * <ul>
 *   <li>{@code dev} profile：不想额外起 Redis 时也能跑通限流逻辑；</li>
 *   <li>单元测试：可确定性地验证“第 N+1 次被拒”与 {@code retryAfter} 的取值。</li>
 * </ul>
 *
 * <p>局限：只在单进程内有效，多实例部署下每个实例各有一份计数，实际总通过量会被放大。
 * 因此默认部署（MySQL + Redis）使用 {@link RedisSlidingWindowRateLimiter}。
 */
@Component
@ConditionalOnProperty(name = "trustagent.runtime.infrastructure", havingValue = "memory")
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public Decision acquire(String key, int limit, int windowSeconds) {
        if (limit <= 0) {
            return Decision.allow(Integer.MAX_VALUE, limit);
        }
        long nowMs = Instant.now().toEpochMilli();
        long windowMs = windowSeconds * 1000L;
        Deque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            long cutoff = nowMs - windowMs;
            while (!window.isEmpty() && window.peekFirst() <= cutoff) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                Long oldest = window.peekFirst();
                long retryAfterMs = oldest == null ? windowMs : (oldest + windowMs) - nowMs;
                long retryAfterSeconds = Math.max(1L, (long) Math.ceil(retryAfterMs / 1000.0));
                return Decision.deny(retryAfterSeconds, limit);
            }
            window.addLast(nowMs);
            int remaining = limit - window.size();
            if (window.isEmpty()) {
                windows.remove(key, window);
            }
            return Decision.allow(remaining, limit);
        }
    }

    /** 测试辅助：清空全部窗口。 */
    public void reset() {
        windows.clear();
    }
}

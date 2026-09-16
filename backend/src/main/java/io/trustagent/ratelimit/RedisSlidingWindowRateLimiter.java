package io.trustagent.ratelimit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis ZSET 的**滑动窗口日志**限流。
 *
 * <h3>算法</h3>
 * 每个限流键对应一个 ZSET，成员是“本次请求的唯一标记”，分值是请求时刻的毫秒时间戳。
 * 每次请求执行：删除窗口外的成员 → 统计窗口内数量 → 若已达上限则拒绝，否则加入当前请求。
 *
 * <h3>为什么是滑动窗口而不是固定窗口计数</h3>
 * 固定窗口（{@code INCR + EXPIRE}）在窗口交界处会出现“双倍突发”：例如 60 秒 30 次，
 * 攻击者在第 59 秒发 30 次、第 61 秒再发 30 次，实际 2 秒内通过了 60 次。
 * 滑动窗口把窗口边界“跟着请求走”，没有这个漏洞。
 *
 * <h3>边界情况（面试常问）</h3>
 * <ul>
 *   <li><b>为什么用 Lua 脚本</b>：清理、计数、写入必须原子完成。如果分三条命令发送，
 *       高并发下多个请求会同时看到“还有余量”而一起放行，实际通过数超过上限。</li>
 *   <li><b>成员为什么带 UUID</b>：同一毫秒内多个请求的分值相同，若成员也相同会被 ZSET 去重，
 *       导致计数偏少、限流失效。</li>
 *   <li><b>内存占用</b>：窗口内每次请求一个成员。上限 L、窗口 W 秒时，最坏 O(L) 个成员/键，
 *       并且设置了 {@code PEXPIRE}，空闲键会自动消失。</li>
 *   <li><b>Redis 不可用时</b>：采取 **fail-open**（放行）并打 ERROR 日志。理由：限流是“降级保护”，
 *       让它把整站流量掐死比短时间少一层保护更糟。这个取舍写进了
 *       docs/SECURITY_LIMITATIONS.md，需要严格限流的场景应改为 fail-closed。</li>
 *   <li><b>精度</b>：以 Redis 服务端与调用方的时间为基准，多实例部署要求各机器时钟大致同步（NTP）。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "trustagent.runtime.infrastructure", havingValue = "redis", matchIfMissing = true)
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);

    /**
     * 返回值约定：负数表示放行，其绝对值为放行后的窗口内计数；正数表示被拒，值为建议重试秒数。
     */
    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local nowMs = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, 0, nowMs - windowMs)
            local count = redis.call('ZCARD', key)
            if count >= limit then
              local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
              local retryMs = windowMs
              if oldest[2] then
                retryMs = (tonumber(oldest[2]) + windowMs) - nowMs
              end
              if retryMs < 1000 then retryMs = 1000 end
              return math.ceil(retryMs / 1000)
            end
            redis.call('ZADD', key, nowMs, member)
            redis.call('PEXPIRE', key, windowMs)
            return -(count + 1)
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Decision acquire(String key, int limit, int windowSeconds) {
        if (limit <= 0) {
            return Decision.allow(Integer.MAX_VALUE, limit);
        }
        long nowMs = Instant.now().toEpochMilli();
        long windowMs = windowSeconds * 1000L;
        String member = nowMs + "-" + UUID.randomUUID();
        try {
            Long result = redisTemplate.execute(SCRIPT, List.of("rl:" + key),
                    String.valueOf(nowMs), String.valueOf(windowMs), String.valueOf(limit), member);
            if (result == null) {
                log.error("限流脚本返回空，按 fail-open 放行 key={}", key);
                return Decision.allow(limit, limit);
            }
            if (result <= 0) {
                int used = (int) -result;
                return Decision.allow(Math.max(0, limit - used), limit);
            }
            return Decision.deny(result, limit);
        } catch (RuntimeException ex) {
            log.error("限流依赖 Redis 异常，按 fail-open 放行 key={} err={}", key, ex.getMessage());
            return Decision.allow(limit, limit);
        }
    }
}

package io.trustagent.ratelimit;

/**
 * 限流器抽象。
 *
 * <p>抽成接口的原因有两个：
 * <ol>
 *   <li>测试时不需要真的启动 Redis（用内存实现即可验证业务逻辑与边界）。</li>
 *   <li>算法可替换（滑动窗口日志 → 令牌桶 → 漏桶），调用方代码不变。</li>
 * </ol>
 */
public interface RateLimiter {

    /**
     * 尝试获取一次配额。
     *
     * @param key           限流键（已包含业务前缀与主体标识）
     * @param limit         窗口内允许的最大请求数
     * @param windowSeconds 窗口长度（秒）
     */
    Decision acquire(String key, int limit, int windowSeconds);

    /**
     * @param allowed           是否放行
     * @param remaining         本窗口剩余可用次数（放行后计算；被拒时为 0）
     * @param retryAfterSeconds 建议的重试等待秒数（放行时为 0）
     * @param limit             配置的上限
     */
    record Decision(boolean allowed, int remaining, long retryAfterSeconds, int limit) {

        public static Decision allow(int remaining, int limit) {
            return new Decision(true, remaining, 0L, limit);
        }

        public static Decision deny(long retryAfterSeconds, int limit) {
            return new Decision(false, 0, retryAfterSeconds, limit);
        }
    }
}

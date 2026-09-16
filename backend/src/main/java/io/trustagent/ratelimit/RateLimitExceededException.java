package io.trustagent.ratelimit;

import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;

/** 触发限流。带上 {@code retryAfterSeconds}，由异常处理器写入 {@code Retry-After} 响应头。 */
public class RateLimitExceededException extends BizException {

    private final long retryAfterSeconds;
    private final String scope;

    public RateLimitExceededException(String scope, long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, "请求过于频繁，请在 " + retryAfterSeconds + " 秒后重试（限流维度: " + scope + "）");
        this.retryAfterSeconds = retryAfterSeconds;
        this.scope = scope;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String scope() {
        return scope;
    }
}

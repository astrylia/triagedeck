package com.triagedeck.ratelimit;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import java.time.Duration;

/** 超过限流。单独写子类是因为它要多带一个数据：还要等多久，用来设置响应头 Retry-After。 */
public class RateLimitExceededException extends BusinessException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super(ErrorCode.TOO_MANY_REQUESTS);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}

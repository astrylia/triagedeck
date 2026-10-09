package com.triagedeck.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yaml 里 triagedeck.rate-limit 下的配置：每条规则是"多长时间内最多多少次"。
 *
 * @param enabled 总开关。测试里默认关掉（见 src/test/resources/application.properties），只在限流测试里打开
 */
@Validated
@ConfigurationProperties("triagedeck.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Valid @NotNull Rule loginPerIp,
        @Valid @NotNull Backoff loginBackoff,
        @Valid @NotNull Rule registerPerIp,
        @Valid @NotNull Rule registrationCodePerIp) {

    public record Rule(@Positive int limit, @NotNull Duration window) {}

    /**
     * 连续失败后的等待规则（见 LoginBackoff）。
     *
     * @param freeAttempts 连续失败几次以内不用等
     * @param baseDelay 超过之后第一次要等多久，之后每次翻倍
     * @param maxDelay 最长等多久
     * @param resetAfter 多久没再尝试，连续失败就清零
     */
    public record Backoff(
            @Positive int freeAttempts,
            @NotNull Duration baseDelay,
            @NotNull Duration maxDelay,
            @NotNull Duration resetAfter) {}
}

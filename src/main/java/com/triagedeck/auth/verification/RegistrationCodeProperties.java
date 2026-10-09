package com.triagedeck.auth.verification;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yaml 里 triagedeck.registration-code 下的配置。
 *
 * @param ttl 验证码多久后失效
 * @param resendCooldown 同一个邮箱两次发码之间至少隔多久，防止有人用接口狂发邮件
 * @param maxAttempts 一个验证码最多能试几次，试满还没对就作废，只能重新获取
 * @param maxFailuresPerEmail 同一个邮箱在 failureWindow 内最多输错几次（换新验证码也累计），超了返回 429
 * @param failureWindow 输错次数的统计窗口，从第一次输错开始算
 */
@Validated
@ConfigurationProperties("triagedeck.registration-code")
public record RegistrationCodeProperties(
        @NotNull Duration ttl,
        @NotNull Duration resendCooldown,
        @Positive int maxAttempts,
        @Positive int maxFailuresPerEmail,
        @NotNull Duration failureWindow) {}

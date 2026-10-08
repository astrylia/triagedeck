package com.triagedeck.auth.verification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yaml 里 triagedeck.mail 下的配置。SMTP 服务器地址本身由 Spring Boot 的 spring.mail 管。
 *
 * @param from 发件人，比如 "TriageDeck &lt;no-reply@example.com&gt;"
 * @param verifyEmailUrl 前端的验证页面地址，邮件里的链接是它加上 ?token=...
 * @param verificationTokenTtl 验证链接的有效期
 * @param resendCooldown 两次发送验证邮件之间至少隔多久，防止有人用接口狂发邮件
 */
@Validated
@ConfigurationProperties("triagedeck.mail")
public record MailSettings(
        @NotBlank String from,
        @NotBlank String verifyEmailUrl,
        @NotNull Duration verificationTokenTtl,
        @NotNull Duration resendCooldown) {}

package com.triagedeck.auth.verification;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yaml 里 triagedeck.mail 下的配置。SMTP 服务器地址本身由 Spring Boot 的 spring.mail 管。
 *
 * @param from 发件人，比如 "TriageDeck &lt;no-reply@example.com&gt;"
 */
@Validated
@ConfigurationProperties("triagedeck.mail")
public record MailSettings(@NotBlank String from) {}

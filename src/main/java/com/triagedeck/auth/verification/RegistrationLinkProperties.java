package com.triagedeck.auth.verification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yaml 里 triagedeck.registration-link 下的配置。
 *
 * @param url 前端设密码页面的地址，邮件里的链接是 url?token=...，前端拿到 token 后调用 POST /api/auth/register
 * @param ttl 链接多久后失效
 * @param resendCooldown 同一个邮箱两次发链接之间至少隔多久，防止有人用接口狂发邮件
 */
@Validated
@ConfigurationProperties("triagedeck.registration-link")
public record RegistrationLinkProperties(
        @NotBlank String url,
        @NotNull Duration ttl,
        @NotNull Duration resendCooldown) {}

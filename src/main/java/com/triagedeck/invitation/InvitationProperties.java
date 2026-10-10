package com.triagedeck.invitation;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yaml 里 triagedeck.invitation 下的配置。
 *
 * @param url 前端邀请页面的地址，邀请邮件里的链接是 url?token=...
 */
@Validated
@ConfigurationProperties("triagedeck.invitation")
public record InvitationProperties(@NotBlank String url) {}

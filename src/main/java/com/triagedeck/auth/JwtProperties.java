package com.triagedeck.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 对应 application.yaml 里的 triagedeck.jwt.* 配置。
 * 加了 @Validated：配置不合法时应用直接启动失败，而不是等到第一次登录才报错。
 */
@Validated
@ConfigurationProperties("triagedeck.jwt")
public record JwtProperties(
        // HS256 要求密钥至少 256 位（32 字节）。字符数 >= 32 时 UTF-8 字节数一定 >= 32
        @NotNull @Size(min = 32) String secret, @NotNull Duration accessTokenTtl) {}

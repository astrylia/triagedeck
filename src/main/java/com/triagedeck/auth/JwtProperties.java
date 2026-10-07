package com.triagedeck.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 对应 application.yaml 里的 triagedeck.jwt.* 配置。 */
@ConfigurationProperties("triagedeck.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl) {}

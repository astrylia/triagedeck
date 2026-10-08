package com.triagedeck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 不启动整个应用，只加载 JwtProperties 这一个配置，检查不合法的配置会让启动失败。 */
class JwtPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void startupFailsWhenSecretIsShorterThan32Bytes() {
        runner.withPropertyValues("triagedeck.jwt.secret=too-short", "triagedeck.jwt.access-token-ttl=15m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startupSucceedsWithValidSecret() {
        runner.withPropertyValues(
                        "triagedeck.jwt.secret=" + "x".repeat(32),
                        "triagedeck.jwt.access-token-ttl=15m",
                        "triagedeck.jwt.refresh-token-ttl=14d")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .getBean(JwtProperties.class)
                        .extracting(JwtProperties::secret)
                        .isEqualTo("x".repeat(32)));
    }
}

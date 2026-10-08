package com.triagedeck;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 测试专用：启动真实的 PostgreSQL 18、Redis 和 Mailpit（假邮箱服务器）容器，并把应用指向它们。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.8")).withExposedPorts(6379);
    }

    @Bean
    GenericContainer<?> mailpitContainer() {
        return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31"))
                .withExposedPorts(Mailpit.SMTP_PORT, Mailpit.HTTP_PORT)
                .waitingFor(Wait.forHttp("/livez").forPort(Mailpit.HTTP_PORT));
    }

    /** Spring Boot 没有现成的邮件服务连接，自己把 spring.mail.host/port 指向容器。 */
    @Bean
    DynamicPropertyRegistrar mailProperties(@Qualifier("mailpitContainer") GenericContainer<?> mailpitContainer) {
        return registry -> {
            registry.add("spring.mail.host", mailpitContainer::getHost);
            registry.add("spring.mail.port", () -> mailpitContainer.getMappedPort(Mailpit.SMTP_PORT));
        };
    }

    @Bean
    Mailpit mailpit(@Qualifier("mailpitContainer") GenericContainer<?> mailpitContainer) {
        return new Mailpit(mailpitContainer);
    }
}

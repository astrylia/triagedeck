package com.triagedeck.ratelimit;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis 卡住不响应时，限流要很快放行，而不是让请求一直等下去。
 *
 * <p>和 RateLimitTest 里"Redis 抛异常"的测试不同，这里用真实的 Redis 容器并把它暂停（docker pause）：
 * TCP 连接还在，但 Redis 不再回复，相当于网络卡住或 Redis 进程僵死。这时只有命令超时能让调用结束，
 * 所以这个测试保护的是 application.yaml 里的 spring.data.redis.timeout。
 * 单独一个测试类，有自己的 Spring 上下文和 Redis 容器，暂停容器不会影响别的测试。
 */
@SpringBootTest(properties = "triagedeck.rate-limit.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RedisOutageTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    @Qualifier("redisContainer")
    GenericContainer<?> redisContainer;

    @Test
    void loginIsAllowedQuicklyWhenRedisStopsResponding() throws Exception {
        // 先正常请求一次，确保应用已经连上 Redis
        login().andExpect(status().isUnauthorized());

        var docker = redisContainer.getDockerClient();
        String id = redisContainer.getContainerId();
        docker.pauseContainerCmd(id).exec();
        try {
            // 登录要查两次 Redis（按 IP、按邮箱），每次最多等一个命令超时，再加上一次 Argon2
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> login().andExpect(status().isUnauthorized()));
        } finally {
            docker.unpauseContainerCmd(id).exec();
        }
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "nobody@acme.com", "password": "wrong-password-123"}
                        """));
    }
}

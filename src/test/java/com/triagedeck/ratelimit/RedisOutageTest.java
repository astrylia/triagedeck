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
    void loginLinkRequestIsAllowedQuicklyWhenRedisStopsResponding() throws Exception {
        // 先正常请求一次，确保应用已经连上 Redis
        sendLink().andExpect(status().isNoContent());

        var docker = redisContainer.getDockerClient();
        String id = redisContainer.getContainerId();
        docker.pauseContainerCmd(id).exec();
        try {
            // 限流查一次 Redis，最多等一个命令超时；发信在后台线程里，不影响接口返回
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> sendLink().andExpect(status().isNoContent()));
        } finally {
            docker.unpauseContainerCmd(id).exec();
        }
    }

    private ResultActions sendLink() throws Exception {
        return mockMvc.perform(post("/api/auth/login-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "alice@acme.com"}
                        """));
    }
}

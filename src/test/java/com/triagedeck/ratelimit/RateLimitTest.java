package com.triagedeck.ratelimit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 限流：计数存在测试容器里的 Redis。把上限调得很小，几次请求就能触发。 */
@SpringBootTest(properties = {"triagedeck.rate-limit.enabled=true", "triagedeck.rate-limit.login-link-per-ip.limit=2"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RateLimitTest {

    @Autowired
    MockMvc mockMvc;

    // 用真实的 Redis，只在"Redis 挂了"那个测试里让它抛异常
    @MockitoSpyBean
    StringRedisTemplate redis;

    @BeforeEach
    void clearCounters() {
        var keys = redis.keys("rate-limit:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void loginLinkIsLimitedPerAddress() throws Exception {
        sendLink("a@acme.com", "10.0.2.1").andExpect(status().isNoContent());
        sendLink("b@acme.com", "10.0.2.1").andExpect(status().isNoContent());

        sendLink("c@acme.com", "10.0.2.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().exists("Retry-After"));

        // 别的地址不受影响
        sendLink("c@acme.com", "10.0.2.2").andExpect(status().isNoContent());
    }

    @Test
    void requestsAreAllowedWhenRedisIsDown() throws Exception {
        doThrow(new RedisConnectionFailureException("Redis down"))
                .when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        for (int i = 0; i < 5; i++) {
            sendLink("user" + i + "@acme.com", "10.0.3.1").andExpect(status().isNoContent());
        }
    }

    private ResultActions sendLink(String email, String address) throws Exception {
        return mockMvc.perform(post("/api/auth/login-link")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email)));
    }
}

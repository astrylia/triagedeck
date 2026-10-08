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
import org.springframework.transaction.annotation.Transactional;

/** 限流：计数存在测试容器里的 Redis。把上限调得很小，几次请求就能触发。 */
@SpringBootTest(
        properties = {
            "triagedeck.rate-limit.enabled=true",
            "triagedeck.rate-limit.login-per-ip.limit=5",
            "triagedeck.rate-limit.login-per-email.limit=3",
            "triagedeck.rate-limit.register-per-ip.limit=2",
            "triagedeck.rate-limit.resend-verification-per-ip.limit=2"
        })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional // 注册的数据回滚；Redis 里的计数不受事务影响，每个测试前单独清掉
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
    void loginIsLimitedPerEmailEvenFromDifferentAddresses() throws Exception {
        login("alice@acme.com", "10.0.0.1").andExpect(status().isUnauthorized());
        login("alice@acme.com", "10.0.0.2").andExpect(status().isUnauthorized());
        login("ALICE@acme.com", "10.0.0.3").andExpect(status().isUnauthorized());

        // 第 4 次：换了 IP、邮箱换了大小写，仍然算同一个邮箱
        login("alice@acme.com", "10.0.0.4")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().exists("Retry-After"));

        // 别的邮箱不受影响
        login("bob@acme.com", "10.0.0.4").andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsLimitedPerAddressAcrossEmails() throws Exception {
        for (int i = 1; i <= 5; i++) {
            login("user" + i + "@acme.com", "10.0.0.9").andExpect(status().isUnauthorized());
        }
        login("user6@acme.com", "10.0.0.9").andExpect(status().isTooManyRequests());
        login("user6@acme.com", "10.0.0.10").andExpect(status().isUnauthorized());
    }

    @Test
    void registrationIsLimitedPerAddress() throws Exception {
        register("a@acme.com", "10.0.1.1").andExpect(status().isCreated());
        register("b@acme.com", "10.0.1.1").andExpect(status().isCreated());

        register("c@acme.com", "10.0.1.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        register("c@acme.com", "10.0.1.2").andExpect(status().isCreated());
    }

    @Test
    void resendIsLimitedPerAddress() throws Exception {
        resend("a@acme.com", "10.0.2.1").andExpect(status().isNoContent());
        resend("b@acme.com", "10.0.2.1").andExpect(status().isNoContent());

        resend("c@acme.com", "10.0.2.1").andExpect(status().isTooManyRequests());
    }

    @Test
    void requestsAreAllowedWhenRedisIsDown() throws Exception {
        doThrow(new RedisConnectionFailureException("Redis down"))
                .when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        for (int i = 0; i < 5; i++) {
            login("alice@acme.com", "10.0.3.1").andExpect(status().isUnauthorized());
        }
    }

    private ResultActions login(String email, String address) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "wrong-password"}
                        """.formatted(email)));
    }

    private ResultActions register(String email, String address) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "correct-horse-battery", "name": "Test"}
                        """.formatted(email)));
    }

    private ResultActions resend(String email, String address) throws Exception {
        return mockMvc.perform(post("/api/auth/resend-verification-email")
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

package com.triagedeck.ratelimit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.auth.verification.RegistrationCodeService;
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
            "triagedeck.rate-limit.login-backoff.free-attempts=3",
            "triagedeck.rate-limit.login-backoff.base-delay=300ms",
            "triagedeck.rate-limit.login-backoff.max-delay=1s",
            "triagedeck.rate-limit.register-per-ip.limit=2",
            "triagedeck.rate-limit.registration-code-per-ip.limit=2"
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

    @Autowired
    RegistrationCodeService registrationCodes;

    @BeforeEach
    void clearCounters() {
        for (String pattern : new String[] {"rate-limit:*", "login-failures:*"}) {
            var keys = redis.keys(pattern);
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        }
    }

    @Test
    void repeatedFailuresOnOneEmailMustWaitEvenFromDifferentAddresses() throws Exception {
        login("alice@acme.com", "10.0.0.1").andExpect(status().isUnauthorized());
        login("alice@acme.com", "10.0.0.2").andExpect(status().isUnauthorized());
        login("ALICE@acme.com", "10.0.0.3").andExpect(status().isUnauthorized());

        // 连续错了 3 次，第 4 次要等：换了 IP、邮箱换了大小写，仍然算同一个邮箱
        login("alice@acme.com", "10.0.0.4")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(header().exists("Retry-After"));

        // 别的邮箱不受影响
        login("bob@acme.com", "10.0.0.4").andExpect(status().isUnauthorized());
    }

    @Test
    void waitDoublesAfterEachFurtherFailure() throws Exception {
        // 每次换一个 IP，避开按 IP 的限制（这个测试里是每分钟 5 次），只看按邮箱的等待
        login("alice@acme.com", "10.0.5.1").andExpect(status().isUnauthorized());
        login("alice@acme.com", "10.0.5.2").andExpect(status().isUnauthorized());
        login("alice@acme.com", "10.0.5.3").andExpect(status().isUnauthorized());
        login("alice@acme.com", "10.0.5.4").andExpect(status().isTooManyRequests());

        // 等过 300 毫秒可以再试一次；刚才被拒的那次不算失败
        Thread.sleep(350);
        login("alice@acme.com", "10.0.5.5").andExpect(status().isUnauthorized());

        // 又错了一次，下一次要等 600 毫秒：过 350 毫秒还不行
        Thread.sleep(350);
        login("alice@acme.com", "10.0.5.6").andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulLoginClearsTheFailures() throws Exception {
        register("alice@acme.com", "10.0.0.6").andExpect(status().isCreated());
        login("alice@acme.com", "10.0.0.6").andExpect(status().isUnauthorized());
        login("alice@acme.com", "10.0.0.6").andExpect(status().isUnauthorized());
        loginWith("alice@acme.com", "correct-horse-battery").andExpect(status().isOk());

        // 成功之后从零算起：又能连续错 3 次，第 4 次才要等
        for (int i = 0; i < 3; i++) {
            login("alice@acme.com", "10.0.0.6").andExpect(status().isUnauthorized());
        }
        login("alice@acme.com", "10.0.0.6").andExpect(status().isTooManyRequests());
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
    void registrationCodeIsLimitedPerAddress() throws Exception {
        sendCode("a@acme.com", "10.0.2.1").andExpect(status().isNoContent());
        sendCode("b@acme.com", "10.0.2.1").andExpect(status().isNoContent());

        sendCode("c@acme.com", "10.0.2.1").andExpect(status().isTooManyRequests());
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

    private ResultActions loginWith(String email, String password) throws Exception {
        return mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(
                                email, password)));
    }

    private ResultActions register(String email, String address) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "code": "%s", "password": "correct-horse-battery", "name": "Test"}
                        """.formatted(email, registrationCodes.issueCode(email))));
    }

    private ResultActions sendCode(String email, String address) throws Exception {
        return mockMvc.perform(post("/api/auth/registration-code")
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

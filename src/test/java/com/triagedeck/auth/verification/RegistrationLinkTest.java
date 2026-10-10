package com.triagedeck.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.Mailpit;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.auth.AuthService;
import com.triagedeck.auth.RegisterRequest;
import com.triagedeck.common.BusinessException;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.user.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 注册链接：发链接、收信、凭链接里的 token 注册，以及冷却、一次性这些限制。
 * 邮件真的经过 SMTP 发到 Mailpit 容器，再从它的 API 读出来；token 真的存在 Redis 容器里。
 *
 * <p>故意不加 @Transactional：发链接在后台线程里查"邮箱是否已注册"，看不到测试方法里没提交的数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RegistrationLinkTest {

    // SecureTokens 生成的 token：43 个 URL 安全的 Base64 字符
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    Mailpit mailpit;

    @Autowired
    RegistrationLinkService registrationLinks;

    @Autowired
    AuthService authService;

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // 用真实的发信组件，只在"发信失败"那个测试里让它抛异常
    @MockitoSpyBean
    JavaMailSender mailSender;

    @BeforeEach
    void clearMailboxAndTokens() {
        mailpit.deleteAll();
        var keys = redis.keys("registration-link*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterEach
    void deleteCommittedRows() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "refresh_token", "app_user");
    }

    @Test
    void linkFromTheEmailCreatesAnAccountThatCanLogIn() throws Exception {
        sendLink("Alice@Acme.com").andExpect(status().isNoContent());
        String text = mailpit.awaitTextsSentTo("alice@acme.com", 1).getFirst();
        assertThat(text).contains("http://localhost:5173/register?token=");
        String token = tokenIn(text);

        register(token, "correct-horse-battery")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@acme.com"));
        login("alice@acme.com").andExpect(status().isOk());

        // 同一个链接只能用一次
        register(token, "correct-horse-battery")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REGISTRATION_LINK"));
    }

    @Test
    void linkIsValidForOneHour() {
        String token = registrationLinks.issueToken("alice@acme.com");

        // 过期由 Redis 自己处理，这里确认 key 带着 1 小时的过期时间；key 里是 token 的哈希，不是 token 本身
        Long secondsLeft = redis.getExpire("registration-link:" + SecureTokens.hash(token));
        assertThat(secondsLeft).isBetween(3590L, 3600L);
        assertThat(redis.hasKey("registration-link:" + token)).isFalse();
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        register(SecureTokens.generate(), "correct-horse-battery")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REGISTRATION_LINK"));
    }

    @Test
    void passwordMadeFromTheEmailIsRejectedAndTheLinkStillWorks() throws Exception {
        String token = registrationLinks.issueToken("alice.smith@acme.com");

        // 请求里没有邮箱，邮箱来自 token；拿自己的邮箱当密码照样被拒
        register(token, "Alice.Smith@acme.com")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
        assertThat(userRepository.findByEmail("alice.smith@acme.com")).isEmpty();

        // 密码不合格不会用掉链接，换个密码重新提交就行
        register(token, "correct-horse-battery").andExpect(status().isCreated());
    }

    @Test
    void sameLinkSubmittedConcurrentlyCreatesOnlyOneAccount() throws Exception {
        String token = registrationLinks.issueToken("alice@acme.com");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            Callable<Boolean> attempt = () -> {
                start.await();
                try {
                    authService.register(new RegisterRequest(token, "correct-horse-battery", "Alice"));
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            };
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(attempt));
            }
            // 8 个线程同时放行，一起用同一个链接注册
            start.countDown();
        }

        long accepted = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                accepted++;
            }
        }
        assertThat(accepted).isEqualTo(1);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void sameEmailGetsAtMostOneLinkPerCooldown() throws Exception {
        sendLink("alice@acme.com").andExpect(status().isNoContent());
        mailpit.awaitTextsSentTo("alice@acme.com", 1);

        // 冷却期内再要：接口照样返回 204，但不会真的再发
        sendLink("alice@acme.com").andExpect(status().isNoContent());
        mailpit.assertStaysAt("alice@acme.com", 1);

        // 删掉冷却 key，模拟过了 60 秒：可以再发
        redis.delete("registration-link-cooldown:alice@acme.com");
        sendLink("alice@acme.com").andExpect(status().isNoContent());
        mailpit.awaitTextsSentTo("alice@acme.com", 2);
    }

    @Test
    void registeredEmailGetsANoticeInsteadOfALink() throws Exception {
        register(registrationLinks.issueToken("alice@acme.com"), "correct-horse-battery")
                .andExpect(status().isCreated());

        // 响应和新邮箱一模一样，没法用这个接口探测谁注册过；邮箱主人收到的是提醒，不是注册链接
        sendLink("alice@acme.com").andExpect(status().isNoContent());
        String text = mailpit.awaitTextsSentTo("alice@acme.com", 1).getFirst();
        assertThat(text).contains("already exists");
        assertThat(TOKEN.matcher(text).find()).isFalse();
    }

    @Test
    void sendLinkReturnsNoContentEvenIfTheEmailCannotBeSent() throws Exception {
        doThrow(new MailSendException("SMTP server down")).when(mailSender).send(any(SimpleMailMessage.class));

        sendLink("alice@acme.com").andExpect(status().isNoContent());

        mailpit.assertStaysAt("alice@acme.com", 0);
    }

    private static String tokenIn(String text) {
        Matcher token = TOKEN.matcher(text);
        assertThat(token.find()).as("registration link in: %s", text).isTrue();
        return token.group(1);
    }

    private ResultActions sendLink(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/registration-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email)));
    }

    private ResultActions register(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "password": "%s", "name": "Alice"}
                        """.formatted(token, password)));
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "correct-horse-battery"}
                        """.formatted(email)));
    }
}

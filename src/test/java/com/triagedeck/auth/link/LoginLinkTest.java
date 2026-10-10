package com.triagedeck.auth.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.Mailpit;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.auth.AuthService;
import com.triagedeck.auth.LoginRequest;
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
 * 登录链接：发链接、收信、凭链接里的 token 登录，以及冷却、一次性这些限制。
 * 邮件真的经过 SMTP 发到 Mailpit 容器，再从它的 API 读出来；token 真的存在 Redis 容器里。
 *
 * <p>故意不加 @Transactional：并发测试里多个线程各自提交事务，测试方法里没提交的数据它们看不到。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginLinkTest {

    // SecureTokens 生成的 token：43 个 URL 安全的 Base64 字符
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    Mailpit mailpit;

    @Autowired
    LoginLinkService loginLinks;

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
        var keys = redis.keys("login-link*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterEach
    void deleteCommittedRows() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "refresh_token", "app_user");
    }

    @Test
    void linkFromTheEmailSignsIn() throws Exception {
        sendLink("Alice@Acme.com").andExpect(status().isNoContent());
        String text = mailpit.awaitTextsSentTo("alice@acme.com", 1).getFirst();
        assertThat(text).contains("http://localhost:5173/login?token=");

        login(tokenIn(text)).andExpect(status().isOk());
        assertThat(userRepository.findByEmail("alice@acme.com")).isPresent();
    }

    @Test
    void linkIsValidFor15Minutes() {
        String token = loginLinks.issueToken("alice@acme.com");

        // 过期由 Redis 自己处理，这里确认 key 带着 15 分钟的过期时间；key 里是 token 的哈希，不是 token 本身
        Long secondsLeft = redis.getExpire("login-link:" + SecureTokens.hash(token));
        assertThat(secondsLeft).isBetween(890L, 900L);
        assertThat(redis.hasKey("login-link:" + token)).isFalse();
    }

    @Test
    void sameLinkSubmittedConcurrentlySignsInOnlyOnce() throws Exception {
        String token = loginLinks.issueToken("alice@acme.com");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            Callable<Boolean> attempt = () -> {
                start.await();
                try {
                    authService.login(new LoginRequest(token));
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            };
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(attempt));
            }
            // 8 个线程同时放行，一起用同一个链接登录
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
    void twoLinksForANewEmailUsedAtOnceCreateOnlyOneAccount() throws Exception {
        // 同一个新邮箱的两个链接同时打开：两个请求都发现"还没有账号"，第二条 INSERT 被唯一约束挡住，改为查出已有账号
        String first = loginLinks.issueToken("alice@acme.com");
        String second = loginLinks.issueToken("alice@acme.com");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (String token : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return authService.login(new LoginRequest(token));
                }));
            }
            start.countDown();
        }

        for (Future<?> result : results) {
            result.get(); // 两个都登录成功，没有抛异常
        }
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
        redis.delete("login-link-cooldown:alice@acme.com");
        sendLink("alice@acme.com").andExpect(status().isNoContent());
        mailpit.awaitTextsSentTo("alice@acme.com", 2);
    }

    @Test
    void sendLinkReturnsNoContentEvenIfTheEmailCannotBeSent() throws Exception {
        doThrow(new MailSendException("SMTP server down")).when(mailSender).send(any(SimpleMailMessage.class));

        sendLink("alice@acme.com").andExpect(status().isNoContent());

        mailpit.assertStaysAt("alice@acme.com", 0);
    }

    private static String tokenIn(String text) {
        Matcher token = TOKEN.matcher(text);
        assertThat(token.find()).as("login link in: %s", text).isTrue();
        return token.group(1);
    }

    private ResultActions sendLink(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email)));
    }

    private ResultActions login(String token) throws Exception {
        return mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token": "%s"}
                        """.formatted(token)));
    }
}

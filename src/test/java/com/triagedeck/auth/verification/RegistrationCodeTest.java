package com.triagedeck.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.Mailpit;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.common.BusinessException;
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
 * 注册验证码：发码、收信、凭验证码注册，以及次数、冷却、一次性这些限制。
 * 邮件真的经过 SMTP 发到 Mailpit 容器，再从它的 API 读出来；验证码真的存在 Redis 容器里。
 *
 * <p>故意不加 @Transactional：发码在后台线程里查"邮箱是否已注册"，看不到测试方法里没提交的数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RegistrationCodeTest {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    Mailpit mailpit;

    @Autowired
    RegistrationCodeService registrationCodes;

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
    void clearMailboxAndCodes() {
        mailpit.deleteAll();
        var keys = redis.keys("registration-code*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @AfterEach
    void deleteCommittedRows() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "refresh_token", "app_user");
    }

    @Test
    void codeFromTheEmailCreatesAnAccountThatCanLogIn() throws Exception {
        sendCode("Alice@Acme.com").andExpect(status().isNoContent());
        String code = codeFromLatestEmailTo("alice@acme.com");

        register("alice@acme.com", code).andExpect(status().isCreated());
        login("alice@acme.com").andExpect(status().isOk());

        // 同一个验证码只能用一次
        register("alice@acme.com", code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    void codeIsValidForTenMinutes() {
        registrationCodes.issueCode("alice@acme.com");

        // 过期由 Redis 自己处理，这里确认 key 带着 10 分钟的过期时间
        Long secondsLeft = redis.getExpire("registration-code:alice@acme.com");
        assertThat(secondsLeft).isBetween(590L, 600L);
    }

    @Test
    void rightCodeStillWorksOnTheFifthTry() throws Exception {
        String code = registrationCodes.issueCode("alice@acme.com");
        for (int i = 0; i < 4; i++) {
            register("alice@acme.com", wrong(code)).andExpect(status().isBadRequest());
        }

        register("alice@acme.com", code).andExpect(status().isCreated());
    }

    @Test
    void codeIsInvalidatedAfterFiveWrongTries() throws Exception {
        String code = registrationCodes.issueCode("alice@acme.com");
        for (int i = 0; i < 5; i++) {
            register("alice@acme.com", wrong(code))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
        }

        // 试错 5 次后，正确的验证码也不能用了，只能重新获取
        register("alice@acme.com", code).andExpect(status().isBadRequest());
        assertThat(userRepository.findByEmail("alice@acme.com")).isEmpty();
    }

    @Test
    void sameCodeSubmittedConcurrentlyIsAcceptedOnlyOnce() throws Exception {
        String code = registrationCodes.issueCode("alice@acme.com");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            Callable<Boolean> attempt = () -> {
                start.await();
                try {
                    registrationCodes.verifyAndConsume("alice@acme.com", code);
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            };
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(attempt));
            }
            // 8 个线程同时放行，一起提交同一个正确的验证码
            start.countDown();
        }

        long accepted = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                accepted++;
            }
        }
        assertThat(accepted).isEqualTo(1);
    }

    @Test
    void sameEmailGetsAtMostOneCodePerCooldown() throws Exception {
        sendCode("alice@acme.com").andExpect(status().isNoContent());
        mailpit.awaitTextsSentTo("alice@acme.com", 1);

        // 冷却期内再要：接口照样返回 204，但不会真的再发
        sendCode("alice@acme.com").andExpect(status().isNoContent());
        mailpit.assertStaysAt("alice@acme.com", 1);

        // 删掉冷却 key，模拟过了 60 秒：可以再发，新验证码能用
        redis.delete("registration-code-cooldown:alice@acme.com");
        sendCode("alice@acme.com").andExpect(status().isNoContent());
        mailpit.awaitTextsSentTo("alice@acme.com", 2);
        register("alice@acme.com", codeFromLatestEmailTo("alice@acme.com")).andExpect(status().isCreated());
    }

    @Test
    void registeredEmailGetsANoticeInsteadOfACode() throws Exception {
        register("alice@acme.com", registrationCodes.issueCode("alice@acme.com"))
                .andExpect(status().isCreated());

        // 响应和新邮箱一模一样，没法用这个接口探测谁注册过；邮箱主人收到的是提醒，不是验证码
        sendCode("alice@acme.com").andExpect(status().isNoContent());
        String text = mailpit.awaitTextsSentTo("alice@acme.com", 1).getFirst();
        assertThat(text).contains("already exists");
        assertThat(SIX_DIGITS.matcher(text).find()).isFalse();
    }

    @Test
    void sendCodeReturnsNoContentEvenIfTheEmailCannotBeSent() throws Exception {
        doThrow(new MailSendException("SMTP server down")).when(mailSender).send(any(SimpleMailMessage.class));

        sendCode("alice@acme.com").andExpect(status().isNoContent());

        mailpit.assertStaysAt("alice@acme.com", 0);
    }

    @Test
    void codeForAnotherEmailIsRejected() {
        String code = registrationCodes.issueCode("alice@acme.com");

        assertThatThrownBy(() -> registrationCodes.verifyAndConsume("bob@acme.com", code))
                .isInstanceOf(BusinessException.class);
    }

    /** 和正确验证码不同的另一个 6 位数。 */
    private static String wrong(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }

    private String codeFromLatestEmailTo(String address) {
        String text = mailpit.awaitTextsSentTo(address, 1).getFirst();
        Matcher code = SIX_DIGITS.matcher(text);
        assertThat(code.find()).as("6-digit code in: %s", text).isTrue();
        return code.group(1);
    }

    private ResultActions sendCode(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/registration-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email)));
    }

    private ResultActions register(String email, String code) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "code": "%s", "password": "correct-horse-battery", "name": "Alice"}
                        """.formatted(email, code)));
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "correct-horse-battery"}
                        """.formatted(email)));
    }
}

package com.triagedeck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.triagedeck.Mailpit;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.user.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
 * 邮箱验证，从注册、收信、点链接到验证成功走一遍完整流程。邮件真的经过 SMTP 发到 Mailpit 容器，再从它的 API 读出来。
 *
 * <p>故意不加 @Transactional：验证邮件在事务提交之后才发，测试方法如果包在一个不提交的事务里，邮件永远不会发出。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmailVerificationTest {

    private static final Pattern VERIFY_LINK = Pattern.compile("http://localhost:5173/verify-email\\?token=([\\w-]+)");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    Mailpit mailpit;

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    EmailVerificationTokenRepository tokenRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // 用真实的发信组件，只在"发信失败"那个测试里让它抛异常
    @MockitoSpyBean
    JavaMailSender mailSender;

    @BeforeEach
    void clearMailbox() {
        mailpit.deleteAll();
    }

    @AfterEach
    void deleteCommittedRows() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "email_verification_token", "refresh_token", "app_user");
    }

    @Test
    void cannotLogInUntilTheLinkInTheEmailIsOpened() throws Exception {
        register("alice@acme.com");
        login("alice@acme.com")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        String token = tokenFromLatestEmailTo("alice@acme.com");
        verify(token).andExpect(status().isNoContent());

        login("alice@acme.com").andExpect(status().isOk());
        // 同一个链接再点一次也没问题
        verify(token).andExpect(status().isNoContent());
    }

    @Test
    void unknownOrExpiredLinkIsRejected() throws Exception {
        verify(SecureTokens.generate())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));

        UUID userId = register("alice@acme.com");
        String expired = SecureTokens.generate();
        tokenRepository.saveAndFlush(new EmailVerificationToken(
                userId, SecureTokens.hash(expired), Instant.now().minusSeconds(1)));

        verify(expired)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
        login("alice@acme.com").andExpect(status().isForbidden());
    }

    @Test
    void resendRespectsTheCooldownAndStopsOnceVerified() throws Exception {
        UUID userId = register("alice@acme.com");

        // 注册时刚发过一封：马上重发，接口照样返回 204，但不会真的再发
        resend("alice@acme.com").andExpect(status().isNoContent());
        assertThat(mailpit.textsSentTo("alice@acme.com")).hasSize(1);

        // 把上一封的发送时间往前挪，模拟过了冷却时间
        jdbcTemplate.update(
                "UPDATE email_verification_token SET created_at = created_at - INTERVAL '2 minutes' WHERE user_id = ?",
                userId);
        resend("Alice@Acme.com").andExpect(status().isNoContent());
        assertThat(mailpit.textsSentTo("alice@acme.com")).hasSize(2);

        // 新链接能用；验证之后再重发，不会再发信
        verify(tokenFromLatestEmailTo("alice@acme.com")).andExpect(status().isNoContent());
        jdbcTemplate.update(
                "UPDATE email_verification_token SET created_at = created_at - INTERVAL '2 minutes' WHERE user_id = ?",
                userId);
        resend("alice@acme.com").andExpect(status().isNoContent());
        assertThat(mailpit.textsSentTo("alice@acme.com")).hasSize(2);
    }

    @Test
    void resendForUnknownEmailLooksTheSameAndSendsNothing() throws Exception {
        // 和已注册邮箱的响应一模一样，没法用这个接口探测谁注册过
        resend("nobody@acme.com").andExpect(status().isNoContent());
        assertThat(mailpit.textsSentTo("nobody@acme.com")).isEmpty();
    }

    @Test
    void registrationSucceedsEvenIfTheEmailCannotBeSent() throws Exception {
        doThrow(new MailSendException("SMTP server down")).when(mailSender).send(any(SimpleMailMessage.class));

        UUID userId = register("alice@acme.com");

        assertThat(userRepository.findById(userId)).isPresent();
        assertThat(mailpit.textsSentTo("alice@acme.com")).isEmpty();
    }

    private String tokenFromLatestEmailTo(String address) {
        List<String> texts = mailpit.textsSentTo(address);
        assertThat(texts).isNotEmpty();
        Matcher link = VERIFY_LINK.matcher(texts.getFirst());
        assertThat(link.find()).as("verification link in: %s", texts.getFirst()).isTrue();
        return link.group(1);
    }

    private UUID register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "correct-horse-battery", "name": "Alice"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions verify(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s"}
                        """.formatted(token)));
    }

    private ResultActions resend(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/resend-verification-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s"}
                        """.formatted(email)));
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "correct-horse-battery"}
                        """.formatted(email)));
    }
}

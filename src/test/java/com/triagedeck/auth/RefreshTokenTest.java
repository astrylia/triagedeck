package com.triagedeck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * refresh token 的轮换、重复使用检测和退出登录。
 *
 * <p>故意不加 @Transactional：要确认"发现重复使用后吊销所有 token"真的提交进了数据库，
 * 而不是被异常带着一起回滚。每个测试结束后手动清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RefreshTokenTest {

    static final String PASSWORD = "correct-horse-battery";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    AppUser alice;

    @BeforeEach
    void setUp() {
        alice = userRepository.saveAndFlush(new AppUser("alice@acme.com", passwordEncoder.encode(PASSWORD), "Alice"));
    }

    @AfterEach
    void deleteCommittedRows() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "refresh_token", "app_user");
    }

    @Test
    void refreshTokenIsExchangedForANewPair() throws Exception {
        String first = refreshTokenOf(login());

        String body = refresh(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 每次都换一个新的 refresh token（轮换）
        assertThat((String) JsonPath.read(body, "$.refreshToken")).isNotEqualTo(first);
        // 新的 access token 能用
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + JsonPath.read(body, "$.accessToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@acme.com"));
    }

    @Test
    void reusingAnOldRefreshTokenRevokesAllOfTheUsersTokens() throws Exception {
        String first = refreshTokenOf(login());
        String second = refreshTokenOf(refresh(first).andExpect(status().isOk()));

        // first 已经用过了，又被拿来用：很可能被偷了
        refresh(first)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // 连合法用户手里最新的 second 也一起作废，必须重新登录
        refresh(second)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String token = refreshTokenOf(login());

        logout(token).andExpect(status().isNoContent());

        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        String token = SecureTokens.generate();
        refreshTokenRepository.saveAndFlush(new RefreshToken(
                alice.getId(), SecureTokens.hash(token), Instant.now().minusSeconds(60)));

        refresh(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        refresh(SecureTokens.generate()).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyOneOfTwoConcurrentRevocationsSucceeds() {
        // 两个请求同时拿同一个 token 来换：条件 UPDATE 保证只有一个能把它从"未吊销"改成"已吊销"
        RefreshToken token = refreshTokenRepository.saveAndFlush(new RefreshToken(
                alice.getId(),
                SecureTokens.hash(SecureTokens.generate()),
                Instant.now().plusSeconds(60)));

        Integer firstTry = transactionTemplate.execute(
                status -> refreshTokenRepository.revokeIfActive(token.getId(), Instant.now()));
        Integer secondTry = transactionTemplate.execute(
                status -> refreshTokenRepository.revokeIfActive(token.getId(), Instant.now()));

        assertThat(firstTry).isEqualTo(1);
        assertThat(secondTry).isZero();
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"email": "alice@acme.com", "password": "%s"}
                        """.formatted(PASSWORD)))
                .andExpect(status().isOk());
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken)));
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mockMvc.perform(
                post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken)));
    }

    private String refreshTokenOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.refreshToken");
    }
}

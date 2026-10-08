package com.triagedeck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional // 每个测试结束后回滚，测试之间互不影响
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppUserRepository userRepository;

    @Test
    void registerCreatesUserWithHashedPassword() throws Exception {
        register("Alice@Acme.com", "correct-horse-battery", "Alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@acme.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        var saved = userRepository.findByEmail("alice@acme.com").orElseThrow();
        assertThat(saved.getPasswordHash())
                .isNotEqualTo("correct-horse-battery")
                .startsWith("{argon2}");
    }

    @Test
    void registerRejectsEmailAlreadyUsedIgnoringCase() throws Exception {
        register("alice@acme.com", "correct-horse-battery", "Alice").andExpect(status().isCreated());

        register("ALICE@acme.com", "another-long-password", "Alice 2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
    }

    @Test
    void registerRejectsInvalidInput() throws Exception {
        register("not-an-email", "short", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("email", "password", "name")));
    }

    @Test
    void registerRequiresPasswordOfAtLeast15Characters() throws Exception {
        register("alice@acme.com", "correct-horse-", "Alice")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));

        register("alice@acme.com", "correct-horse-b", "Alice").andExpect(status().isCreated());
    }

    @Test
    void registerRejectsCommonOrGuessablePasswords() throws Exception {
        // 出现在常见泄露密码列表里（比较时忽略大小写）
        register("alice@acme.com", "1Q2W3E4R5T6Y7U8I9O0P", "Alice")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("too common")));

        // 直接拿自己的邮箱当密码
        register("alice.smith@acme.com", "Alice.Smith@acme.com", "Alice")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));

        assertThat(userRepository.findByEmail("alice@acme.com")).isEmpty();
        assertThat(userRepository.findByEmail("alice.smith@acme.com")).isEmpty();
    }

    @Test
    void registerAcceptsLongUnicodePasswordUpTo128Characters() throws Exception {
        // 64 个汉字 = 192 字节：bcrypt 的 72 字节上限会拒绝它，Argon2 没有这个限制
        String chinesePassword = "一二三四五六七八".repeat(8);
        register("alice@acme.com", chinesePassword, "Alice").andExpect(status().isCreated());
        login("alice@acme.com", chinesePassword).andExpect(status().isOk());

        register("bob@acme.com", "x".repeat(129), "Bob")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void userWithLegacyBcryptHashCanStillLogIn() throws Exception {
        // 模拟换算法之前注册的用户：数据库里存的是 {bcrypt} 前缀的哈希
        String legacyHash = "{bcrypt}" + new BCryptPasswordEncoder().encode("old-password");
        userRepository.save(new AppUser("legacy@acme.com", legacyHash, "Legacy"));

        login("legacy@acme.com", "old-password").andExpect(status().isOk());
    }

    @Test
    void loginReturnsTokenThatAuthenticatesRequests() throws Exception {
        String userId = JsonPath.read(
                register("alice@acme.com", "correct-horse-battery", "Alice")
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");

        String token = JsonPath.read(
                login("Alice@acme.com", "correct-horse-battery")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.tokenType").value("Bearer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.accessToken");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value("alice@acme.com"));
    }

    @Test
    void loginRejectsWrongPasswordAndUnknownEmailTheSameWay() throws Exception {
        register("alice@acme.com", "correct-horse-battery", "Alice").andExpect(status().isCreated());

        String wrongPassword = login("alice@acme.com", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknownEmail = login("nobody@acme.com", "correct-horse-battery")
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // 两种失败的响应体必须完全一样，否则攻击者能据此判断邮箱是否注册过
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void protectedEndpointRequiresValidToken() throws Exception {
        // 没带 token 和 token 无效，都返回和其他错误一样格式的 401（ProblemDetail + code）
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private ResultActions register(String email, String password, String name) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "name": "%s"}
                        """.formatted(email, password, name)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(
                                email, password)));
    }
}

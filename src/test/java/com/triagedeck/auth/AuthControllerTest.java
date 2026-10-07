package com.triagedeck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
        register("Alice@Acme.com", "correct-horse", "Alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@acme.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        var saved = userRepository.findByEmail("alice@acme.com").orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo("correct-horse").startsWith("{bcrypt}");
    }

    @Test
    void registerRejectsEmailAlreadyUsedIgnoringCase() throws Exception {
        register("alice@acme.com", "correct-horse", "Alice").andExpect(status().isCreated());

        register("ALICE@acme.com", "another-pass", "Alice 2")
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
    void loginReturnsTokenThatAuthenticatesRequests() throws Exception {
        String userId = JsonPath.read(
                register("alice@acme.com", "correct-horse", "Alice")
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");

        String token = JsonPath.read(
                login("Alice@acme.com", "correct-horse")
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
        register("alice@acme.com", "correct-horse", "Alice").andExpect(status().isCreated());

        String wrongPassword = login("alice@acme.com", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknownEmail = login("nobody@acme.com", "correct-horse")
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // 两种失败的响应体必须完全一样，否则攻击者能据此判断邮箱是否注册过
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void protectedEndpointRequiresValidToken() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
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

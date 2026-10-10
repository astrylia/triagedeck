package com.triagedeck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.auth.link.LoginLinkService;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    @Autowired
    LoginLinkService loginLinks;

    @Test
    void firstLoginCreatesAnAccountNamedAfterTheEmail() throws Exception {
        String token = accessTokenFrom(login(loginLinks.issueToken("Alice.Smith@Acme.com")));

        me(token)
                .andExpect(jsonPath("$.email").value("alice.smith@acme.com"))
                .andExpect(jsonPath("$.name").value("alice.smith"));
    }

    @Test
    void loginUsesTheExistingAccount() throws Exception {
        AppUser alice = userRepository.saveAndFlush(new AppUser("alice@acme.com", "Alice"));

        String token = accessTokenFrom(login(loginLinks.issueToken("alice@acme.com")));

        me(token)
                .andExpect(jsonPath("$.id").value(alice.getId().toString()))
                .andExpect(jsonPath("$.name").value("Alice"));
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void loginLinkWorksOnlyOnce() throws Exception {
        String link = loginLinks.issueToken("alice@acme.com");
        login(link).andExpect(status().isOk());

        login(link)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOGIN_LINK"));
    }

    @Test
    void unknownLinkIsRejected() throws Exception {
        login(SecureTokens.generate())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOGIN_LINK"));
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void loginRejectsBlankToken() throws Exception {
        login("")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("token"));
    }

    @Test
    void accessTokenIsSignedWithHs256() throws Exception {
        String token = accessTokenFrom(login(loginLinks.issueToken("alice@acme.com")));

        // JWT 第一段是 header：签名算法必须是 HS256，和解码器只接受的算法一致
        String jwtHeader = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(jwtHeader, "$.alg")).isEqualTo("HS256");
    }

    @Test
    void userCanChangeTheirName() throws Exception {
        String token = accessTokenFrom(login(loginLinks.issueToken("alice@acme.com")));

        rename(token, "Alice Smith")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice Smith"));
        me(token).andExpect(jsonPath("$.name").value("Alice Smith"));

        rename(token, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
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

    private ResultActions login(String linkToken) throws Exception {
        return mockMvc.perform(
                post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"token": "%s"}
                        """.formatted(linkToken)));
    }

    private static String accessTokenFrom(ResultActions login) throws Exception {
        String body = login.andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    private ResultActions rename(String accessToken, String name) throws Exception {
        return mockMvc.perform(patch("/api/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s"}
                        """.formatted(name)));
    }
}

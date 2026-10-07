package com.triagedeck.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模拟两个请求同时注册同一个邮箱：让 existsByEmail 永远返回 false，
 * 相当于两个请求都已经通过了"邮箱是否存在"的检查，第二条 INSERT 只能被数据库的唯一约束挡住。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ConcurrentRegistrationTest {

    @Autowired
    MockMvc mockMvc;

    // 用真实的 repository，只把 existsByEmail 这一个方法替换掉
    @MockitoSpyBean
    AppUserRepository userRepository;

    @Test
    void registerReturnsConflictWhenDatabaseRejectsDuplicateEmail() throws Exception {
        doReturn(false).when(userRepository).existsByEmail(anyString());

        register("alice@acme.com").andExpect(status().isCreated());

        register("alice@acme.com")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
    }

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "correct-horse", "name": "Alice"}
                        """.formatted(email)));
    }
}

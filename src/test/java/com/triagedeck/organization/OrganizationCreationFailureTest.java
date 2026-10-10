package com.triagedeck.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.auth.token.TokenService;
import com.triagedeck.membership.MembershipRepository;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 创建组织过程中出错的情况。
 *
 * <p>这个类故意不加 @Transactional：加了的话，整个测试方法和被测代码共用同一个事务，
 * 数据直到测试结束才回滚，就看不出"创建组织的事务有没有把已插入的组织撤销掉"。
 * 代价是数据会真正写进数据库，所以每个测试结束后要手动清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrganizationCreationFailureTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    TokenService tokenService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // 用真实的 repository，只替换个别方法，用来制造"出错"的场景
    @MockitoSpyBean
    OrganizationRepository organizationRepository;

    @MockitoSpyBean
    MembershipRepository membershipRepository;

    @AfterEach
    void deleteCommittedRows() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "membership", "organization", "app_user");
    }

    @Test
    void organizationIsNotKeptWhenSavingOwnerMembershipFails() throws Exception {
        // 组织已经插入成功，接着保存 OWNER 成员关系时出错
        doThrow(new IllegalStateException("simulated failure"))
                .when(membershipRepository)
                .save(any());
        doThrow(new IllegalStateException("simulated failure"))
                .when(membershipRepository)
                .saveAndFlush(any());

        createOrganization(tokenFor(saveUser("alice@acme.com")), "acme").andExpect(status().isInternalServerError());

        // 没有 OWNER 的组织谁也管理不了，所以组织本身也不能留下来
        assertThat(JdbcTestUtils.countRowsInTable(jdbcTemplate, "organization")).isZero();
    }

    @Test
    void createReturnsConflictWhenDatabaseRejectsDuplicateSlug() throws Exception {
        // 模拟两个请求同时用同一个 slug：都通过了"slug 是否存在"的检查，第二条 INSERT 只能被唯一约束挡住
        doReturn(false).when(organizationRepository).existsBySlug(anyString());

        createOrganization(tokenFor(saveUser("alice@acme.com")), "acme").andExpect(status().isCreated());

        createOrganization(tokenFor(saveUser("bob@other.com")), "acme")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORG_SLUG_ALREADY_USED"));
    }

    private AppUser saveUser(String email) {
        return userRepository.saveAndFlush(new AppUser(email, "Test User"));
    }

    private String tokenFor(AppUser user) {
        return tokenService.issueAccessToken(user).accessToken();
    }

    private ResultActions createOrganization(String token, String slug) throws Exception {
        return mockMvc.perform(post("/api/orgs")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Acme", "slug": "%s"}
                        """.formatted(slug)));
    }
}

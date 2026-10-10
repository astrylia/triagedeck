package com.triagedeck.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.MembershipId;
import com.triagedeck.membership.MembershipRepository;
import com.triagedeck.membership.Role;
import com.triagedeck.organization.Organization;
import com.triagedeck.organization.OrganizationRepository;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 接受邀请。
 *
 * <p>这个类故意不加 @Transactional：每个请求的事务真正提交，之后再从数据库读，
 * 才能确认"邀请被标记为已接受"真的写进了数据库。代价是每个测试结束后要手动清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InvitationAcceptTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // 用真实的 repository，只在并发测试里替换个别方法
    @MockitoSpyBean
    MembershipRepository membershipRepository;

    AppUser alice;
    AppUser bob;
    UUID orgId;

    @BeforeEach
    void setUp() {
        alice = saveUser("alice@acme.com");
        bob = saveUser("bob@other.com");
        Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
        orgId = org.getId();
        membershipRepository.saveAndFlush(new Membership(alice.getId(), orgId, Role.OWNER));
    }

    @AfterEach
    void deleteCommittedRows() {
        JdbcTestUtils.deleteFromTables(
                jdbcTemplate, "invitation", "membership", "refresh_token", "organization", "app_user");
    }

    @Test
    void inviteeBecomesMemberWithTheInvitedRoleAndIsSignedIn() throws Exception {
        String token = inviteBob(Role.AGENT, Duration.ofDays(7));

        String body = accept(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(orgId.toString()))
                .andExpect(jsonPath("$.role").value("AGENT"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Membership membership = membershipRepository
                .findById(new MembershipId(bob.getId(), orgId))
                .orElseThrow();
        assertThat(membership.getRole()).isEqualTo(Role.AGENT);
        assertThat(invitationRepository.findByTokenHash(SecureTokens.hash(token)))
                .get()
                .extracting(Invitation::getAcceptedAt)
                .isNotNull();
        // 响应里的 access token 就是 Bob 的，拿它就能看这个组织
        String accessToken = JsonPath.read(body, "$.tokens.accessToken");
        mockMvc.perform(get("/api/orgs/" + orgId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void newcomerGetsAnAccountAndJoins() throws Exception {
        String token = invite("carol@new.com", Role.CUSTOMER, Duration.ofDays(7));

        String body = accept(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        AppUser carol = userRepository.findByEmail("carol@new.com").orElseThrow();
        assertThat(carol.getName()).isEqualTo("carol");
        assertThat(membershipRepository.existsById(new MembershipId(carol.getId(), orgId)))
                .isTrue();
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + JsonPath.read(body, "$.tokens.accessToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("carol@new.com"));
    }

    @Test
    void invitationCanOnlyBeUsedOnce() throws Exception {
        String token = inviteBob(Role.AGENT, Duration.ofDays(7));
        accept(token).andExpect(status().isOk());

        accept(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
    }

    @Test
    void expiredInvitationIsRejectedWithoutCreatingAnAccount() throws Exception {
        String token = invite("carol@new.com", Role.AGENT, Duration.ofMinutes(-1));

        accept(token).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
        assertThat(userRepository.findByEmail("carol@new.com")).isEmpty();
    }

    @Test
    void unknownTokenGetsNotFound() throws Exception {
        accept(SecureTokens.generate())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
    }

    @Test
    void existingMemberCannotJoinAgain() throws Exception {
        membershipRepository.saveAndFlush(new Membership(bob.getId(), orgId, Role.AGENT));
        String token = inviteBob(Role.ADMIN, Duration.ofDays(7));

        accept(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
        // 原来的角色没被改掉
        assertThat(membershipRepository.findById(new MembershipId(bob.getId(), orgId)))
                .get()
                .extracting(Membership::getRole)
                .isEqualTo(Role.AGENT);
    }

    @Test
    void concurrentAcceptReturnsConflictInsteadOfServerError() throws Exception {
        // 模拟 Bob 同时点了两次：另一个请求刚刚插入了成员关系，这个请求的"是否已是成员"检查却没看到它，
        // 只能靠成员关系的主键 (user_id, org_id) 挡住第二条 INSERT
        membershipRepository.saveAndFlush(new Membership(bob.getId(), orgId, Role.AGENT));
        doReturn(false).when(membershipRepository).existsById(any());
        doReturn(Optional.empty()).when(membershipRepository).findById(any());
        String token = inviteBob(Role.AGENT, Duration.ofDays(7));

        accept(token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    }

    /** 直接往数据库里放一个发给 Bob 的邀请，返回原始 token。validFor 是负数就是已经过期的邀请。 */
    private String inviteBob(Role role, Duration validFor) {
        return invite(bob.getEmail(), role, validFor);
    }

    /** 直接往数据库里放一个发给 email 的邀请，返回原始 token。 */
    private String invite(String email, Role role, Duration validFor) {
        String token = SecureTokens.generate();
        invitationRepository.saveAndFlush(new Invitation(
                orgId,
                email,
                role,
                SecureTokens.hash(token),
                alice.getId(),
                Instant.now().plus(validFor)));
        return token;
    }

    private AppUser saveUser(String email) {
        return userRepository.saveAndFlush(new AppUser(email, "Test User"));
    }

    /** 凭邀请链接里的 token 加入，不带 access token：这个接口不需要先登录。 */
    private ResultActions accept(String token) throws Exception {
        return mockMvc.perform(post("/api/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s"}
                        """.formatted(token)));
    }
}

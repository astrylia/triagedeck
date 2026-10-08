package com.triagedeck.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.auth.TokenService;
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
    TokenService tokenService;

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
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "invitation", "membership", "organization", "app_user");
    }

    @Test
    void inviteeBecomesMemberWithTheInvitedRole() throws Exception {
        String token = inviteBob(Role.AGENT, Duration.ofDays(7));

        accept(bob, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(orgId.toString()))
                .andExpect(jsonPath("$.role").value("AGENT"));

        Membership membership = membershipRepository
                .findById(new MembershipId(bob.getId(), orgId))
                .orElseThrow();
        assertThat(membership.getRole()).isEqualTo(Role.AGENT);
        assertThat(invitationRepository.findByTokenHash(InvitationTokens.hash(token)))
                .get()
                .extracting(Invitation::getAcceptedAt)
                .isNotNull();
        // 加入后就能看这个组织了
        mockMvc.perform(get("/api/orgs/" + orgId).header("Authorization", "Bearer " + tokenFor(bob)))
                .andExpect(status().isOk());
    }

    @Test
    void invitationCanOnlyBeUsedOnce() throws Exception {
        String token = inviteBob(Role.AGENT, Duration.ofDays(7));
        accept(bob, token).andExpect(status().isOk());

        accept(bob, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
    }

    @Test
    void expiredInvitationIsRejected() throws Exception {
        String token = inviteBob(Role.AGENT, Duration.ofMinutes(-1));

        accept(bob, token)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
        assertThat(membershipRepository.existsById(new MembershipId(bob.getId(), orgId)))
                .isFalse();
    }

    @Test
    void someoneElseCannotUseTheInvitation() throws Exception {
        // 链接被转发给了 Mallory：她登录了自己的账号，邮箱和邀请的不一样
        String token = inviteBob(Role.ADMIN, Duration.ofDays(7));
        AppUser mallory = saveUser("mallory@evil.com");

        accept(mallory, token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_EMAIL_MISMATCH"));
        assertThat(membershipRepository.existsById(new MembershipId(mallory.getId(), orgId)))
                .isFalse();

        // 邀请没有被消耗掉，Bob 本人还能用
        accept(bob, token).andExpect(status().isOk());
    }

    @Test
    void unknownTokenGetsNotFound() throws Exception {
        accept(bob, InvitationTokens.generate())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
    }

    @Test
    void existingMemberCannotJoinAgain() throws Exception {
        membershipRepository.saveAndFlush(new Membership(bob.getId(), orgId, Role.AGENT));
        String token = inviteBob(Role.ADMIN, Duration.ofDays(7));

        accept(bob, token)
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

        accept(bob, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    }

    /** 直接往数据库里放一个发给 Bob 的邀请，返回原始 token。validFor 是负数就是已经过期的邀请。 */
    private String inviteBob(Role role, Duration validFor) {
        String token = InvitationTokens.generate();
        invitationRepository.saveAndFlush(new Invitation(
                orgId,
                bob.getEmail(),
                role,
                InvitationTokens.hash(token),
                alice.getId(),
                Instant.now().plus(validFor)));
        return token;
    }

    private AppUser saveUser(String email) {
        return userRepository.saveAndFlush(new AppUser(email, "{noop}unused", "Test User"));
    }

    private String tokenFor(AppUser user) {
        return tokenService.issueAccessToken(user).accessToken();
    }

    private ResultActions accept(AppUser user, String token) throws Exception {
        return mockMvc.perform(post("/api/invitations/accept")
                .header("Authorization", "Bearer " + tokenFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s"}
                        """.formatted(token)));
    }
}

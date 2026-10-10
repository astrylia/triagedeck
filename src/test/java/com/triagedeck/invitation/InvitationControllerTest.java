package com.triagedeck.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.Mailpit;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.auth.token.TokenService;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.MembershipRepository;
import com.triagedeck.membership.Role;
import com.triagedeck.organization.Organization;
import com.triagedeck.organization.OrganizationRepository;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
@Transactional
class InvitationControllerTest {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]{43})");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    MembershipRepository membershipRepository;

    @Autowired
    InvitationRepository invitationRepository;

    @Autowired
    TokenService tokenService;

    @Autowired
    Mailpit mailpit;

    @Test
    void ownerCanInviteAndTheLinkIsEmailedToTheInvitee() throws Exception {
        AppUser alice = saveUser("alice@acme.com");
        UUID orgId = saveOrganizationWith(alice, Role.OWNER);
        mailpit.deleteAll();

        invite(alice, orgId, "Bob@Other.COM", "AGENT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("bob@other.com"))
                .andExpect(jsonPath("$.role").value("AGENT"))
                // 邀请人拿不到 token，链接只发到被邀请的邮箱
                .andExpect(jsonPath("$.token").doesNotExist());

        String text = mailpit.awaitTextsSentTo("bob@other.com", 1).getFirst();
        assertThat(text).contains("Acme").contains("AGENT").contains("http://localhost:5173/invitations?token=");
        Matcher link = TOKEN.matcher(text);
        assertThat(link.find()).isTrue();
        String token = link.group(1);
        List<Invitation> saved = invitationRepository.findAll();
        assertThat(saved).hasSize(1);
        Invitation invitation = saved.getFirst();
        // 数据库里存的是哈希，不是原始 token
        assertThat(invitation.getTokenHash()).isNotEqualTo(token).isEqualTo(SecureTokens.hash(token));
        assertThat(invitation.getOrgId()).isEqualTo(orgId);
        assertThat(invitation.getInvitedBy()).isEqualTo(alice.getId());
        assertThat(invitation.getAcceptedAt()).isNull();
        assertThat(invitation.getExpiresAt())
                .isCloseTo(Instant.now().plus(InvitationService.VALID_FOR), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void adminCanInvite() throws Exception {
        AppUser carol = saveUser("carol@acme.com");
        UUID orgId = saveOrganizationWith(carol, Role.ADMIN);

        invite(carol, orgId, "bob@other.com", "CUSTOMER").andExpect(status().isCreated());
    }

    @Test
    void memberWithLowerRoleGetsForbidden() throws Exception {
        AppUser dave = saveUser("dave@acme.com");
        UUID orgId = saveOrganizationWith(dave, Role.AGENT);

        invite(dave, orgId, "bob@other.com", "CUSTOMER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_ROLE"));
        assertThat(invitationRepository.count()).isZero();
    }

    @Test
    void nonMemberGetsNotFound() throws Exception {
        // 和查看组织一样：不是成员就当组织不存在，不能返回 403 暴露组织存在
        UUID orgId = saveOrganizationWith(saveUser("alice@acme.com"), Role.OWNER);
        AppUser mallory = saveUser("mallory@evil.com");

        invite(mallory, orgId, "mallory2@evil.com", "ADMIN")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORG_NOT_FOUND"));
    }

    @Test
    void cannotInviteSomeoneAsOwner() throws Exception {
        AppUser alice = saveUser("alice@acme.com");
        UUID orgId = saveOrganizationWith(alice, Role.OWNER);

        invite(alice, orgId, "bob@other.com", "OWNER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("roleInvitable"));
    }

    private AppUser saveUser(String email) {
        return userRepository.saveAndFlush(new AppUser(email, "Test User"));
    }

    /** 直接往数据库里放一个组织和一条成员关系，不走创建组织的接口，这样能指定任意角色。 */
    private UUID saveOrganizationWith(AppUser member, Role role) {
        Organization org = organizationRepository.saveAndFlush(
                new Organization("Acme", "acme-" + UUID.randomUUID().toString().substring(0, 8)));
        membershipRepository.saveAndFlush(new Membership(member.getId(), org.getId(), role));
        return org.getId();
    }

    private ResultActions invite(AppUser inviter, UUID orgId, String email, String role) throws Exception {
        String token = tokenService.issueAccessToken(inviter).accessToken();
        return mockMvc.perform(post("/api/orgs/" + orgId + "/invitations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "role": "%s"}
                        """.formatted(email, role)));
    }
}

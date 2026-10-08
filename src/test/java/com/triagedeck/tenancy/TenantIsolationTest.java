package com.triagedeck.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.auth.TokenService;
import com.triagedeck.invitation.InvitationRepository;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.MembershipRepository;
import com.triagedeck.membership.Role;
import com.triagedeck.organization.Organization;
import com.triagedeck.organization.OrganizationRepository;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 租户隔离：B 组织的成员拿着 A 组织真实的 id，调用 A 组织下的每一个接口，都只能得到 404，且什么都改不了。
 *
 * <p>另外有一个"清单检查"：扫描应用里所有路径带 {orgId} 的接口，必须都出现在下面的 ORG_SCOPED_ENDPOINTS 里。
 * 以后新增一个组织下的接口却忘了在这里登记，这个测试就会失败，提醒你补上隔离测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TenantIsolationTest {

    /** 一个组织下的接口：HTTP 方法、路径模板、请求体（没有就是 null）。 */
    record Endpoint(HttpMethod method, String path, String body) {
        String key() {
            return method.name() + " " + path;
        }

        @Override
        public String toString() {
            return key();
        }
    }

    static final List<Endpoint> ORG_SCOPED_ENDPOINTS = List.of(
            new Endpoint(HttpMethod.GET, "/api/orgs/{orgId}", null),
            new Endpoint(HttpMethod.POST, "/api/orgs/{orgId}/invitations", """
                    {"email": "victim@acme.com", "role": "ADMIN"}
                    """));

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
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    UUID orgA;
    AppUser bob;

    @BeforeEach
    void setUp() {
        AppUser alice = saveUser("alice@acme.com");
        orgA = saveOrganization("acme", alice, Role.OWNER);
        // Bob 是另一个组织的 OWNER：在自己的组织里权限最高，但和 A 组织毫无关系
        bob = saveUser("bob@other.com");
        saveOrganization("other", bob, Role.OWNER);
    }

    static List<Endpoint> orgScopedEndpoints() {
        return ORG_SCOPED_ENDPOINTS;
    }

    @ParameterizedTest
    @MethodSource("orgScopedEndpoints")
    void memberOfAnotherOrganizationGetsNotFound(Endpoint endpoint) throws Exception {
        var request = request(endpoint.method(), endpoint.path(), orgA)
                .header(
                        "Authorization",
                        "Bearer " + tokenService.issueAccessToken(bob).accessToken());
        if (endpoint.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
        }

        mockMvc.perform(request)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORG_NOT_FOUND"));

        // 什么都没被改：Bob 没混进 A 组织，也没在 A 组织里发出邀请
        assertThat(membershipRepository.findByIdUserId(bob.getId()))
                .extracting(membership -> membership.getId().orgId())
                .doesNotContain(orgA);
        assertThat(invitationRepository.count()).isZero();
    }

    @Test
    void everyOrgScopedEndpointIsCoveredByThisTest() {
        Set<String> inApplication = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()
                        .filter(path -> path.contains("{orgId}"))
                        .flatMap(path -> info.getMethodsCondition().getMethods().stream()
                                .map(method -> method.name() + " " + path)))
                .collect(Collectors.toSet());
        Set<String> covered = ORG_SCOPED_ENDPOINTS.stream().map(Endpoint::key).collect(Collectors.toSet());

        assertThat(covered)
                .as("新增了组织下的接口？把它加进 ORG_SCOPED_ENDPOINTS，证明别的组织的人访问不到")
                .containsAll(inApplication);
    }

    private AppUser saveUser(String email) {
        return userRepository.saveAndFlush(new AppUser(email, "{noop}unused", "Test User"));
    }

    private UUID saveOrganization(String slug, AppUser owner, Role role) {
        Organization org = organizationRepository.saveAndFlush(new Organization(slug, slug));
        membershipRepository.saveAndFlush(new Membership(owner.getId(), org.getId(), role));
        return org.getId();
    }
}

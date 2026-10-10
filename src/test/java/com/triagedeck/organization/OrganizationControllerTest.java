package com.triagedeck.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.auth.token.TokenService;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.MembershipId;
import com.triagedeck.membership.MembershipRepository;
import com.triagedeck.membership.Role;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.util.UUID;
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
class OrganizationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    MembershipRepository membershipRepository;

    @Autowired
    TokenService tokenService;

    @Test
    void createOrganizationMakesCallerItsOwner() throws Exception {
        AppUser alice = saveUser("alice@acme.com");

        String body = createOrganization(tokenFor(alice), "Acme Support", "acme-support")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Acme Support"))
                .andExpect(jsonPath("$.slug").value("acme-support"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID orgId = UUID.fromString(JsonPath.read(body, "$.id"));
        Membership membership = membershipRepository
                .findById(new MembershipId(alice.getId(), orgId))
                .orElseThrow();
        assertThat(membership.getRole()).isEqualTo(Role.OWNER);
    }

    @Test
    void createOrganizationRejectsSlugAlreadyTaken() throws Exception {
        createOrganization(tokenFor(saveUser("alice@acme.com")), "Acme", "acme").andExpect(status().isCreated());

        createOrganization(tokenFor(saveUser("bob@other.com")), "Another Acme", "acme")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORG_SLUG_ALREADY_USED"));
    }

    @Test
    void createOrganizationRejectsInvalidInput() throws Exception {
        createOrganization(tokenFor(saveUser("alice@acme.com")), "", "Acme Support")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("name", "slug")));
    }

    @Test
    void createOrganizationRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"name": "Acme", "slug": "acme"}
                        """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsOnlyOrganizationsTheCallerBelongsTo() throws Exception {
        String alice = tokenFor(saveUser("alice@acme.com"));
        String bob = tokenFor(saveUser("bob@other.com"));
        createOrganization(alice, "Acme", "acme").andExpect(status().isCreated());
        createOrganization(alice, "Acme Labs", "acme-labs").andExpect(status().isCreated());
        createOrganization(bob, "Other", "other").andExpect(status().isCreated());

        mockMvc.perform(get("/api/orgs").header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug").value(containsInAnyOrder("acme", "acme-labs")));
    }

    @Test
    void memberCanViewOrganization() throws Exception {
        String alice = tokenFor(saveUser("alice@acme.com"));
        String orgId = idOf(createOrganization(alice, "Acme", "acme"));

        mockMvc.perform(get("/api/orgs/" + orgId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("acme"));
    }

    @Test
    void nonMemberGetsNotFoundEvenWithRealOrganizationId() throws Exception {
        // 跨租户访问：Bob 拿到了 Alice 组织真实的 id，也不能看
        String orgId = idOf(createOrganization(tokenFor(saveUser("alice@acme.com")), "Acme", "acme"));
        String bob = tokenFor(saveUser("bob@other.com"));

        mockMvc.perform(get("/api/orgs/" + orgId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORG_NOT_FOUND"));
    }

    @Test
    void unknownOrganizationIdGetsTheSameNotFound() throws Exception {
        // 和上一个测试的响应一样，外人分不出"组织不存在"和"组织存在但我不是成员"
        String alice = tokenFor(saveUser("alice@acme.com"));

        mockMvc.perform(get("/api/orgs/" + UUID.randomUUID()).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORG_NOT_FOUND"));
    }

    private String idOf(ResultActions createResult) throws Exception {
        return JsonPath.read(createResult.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private AppUser saveUser(String email) {
        // 测试只需要一个已存在的用户和它的 token，不走注册接口，密码哈希随便填
        return userRepository.saveAndFlush(new AppUser(email, "Test User"));
    }

    private String tokenFor(AppUser user) {
        return tokenService.issueAccessToken(user).accessToken();
    }

    private ResultActions createOrganization(String token, String name, String slug) throws Exception {
        return mockMvc.perform(post("/api/orgs")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "slug": "%s"}
                        """.formatted(name, slug)));
    }
}

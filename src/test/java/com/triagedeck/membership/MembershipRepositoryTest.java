package com.triagedeck.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triagedeck.TestcontainersConfiguration;
import com.triagedeck.organization.Organization;
import com.triagedeck.organization.OrganizationRepository;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class MembershipRepositoryTest {

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    AppUserRepository userRepository;

    @Autowired
    MembershipRepository membershipRepository;

    @Test
    void saveRejectsDuplicateMembership() {
        // 准备：外键要求用户和组织真实存在，所以先存它们
        Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
        AppUser alice = userRepository.saveAndFlush(new AppUser("alice@acme.com", "hash", "Alice"));
        membershipRepository.saveAndFlush(new Membership(alice.getId(), org.getId(), Role.OWNER));

        // 同一用户在同一组织再存一条：必须报错，而不是悄悄把 OWNER 改成 AGENT
        Membership duplicate = new Membership(alice.getId(), org.getId(), Role.AGENT);
        assertThatThrownBy(() -> membershipRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByIdUserIdReturnsOnlyThatUsersMemberships() {
        Organization acme = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
        Organization globex = organizationRepository.saveAndFlush(new Organization("Globex", "globex"));
        AppUser alice = userRepository.saveAndFlush(new AppUser("alice@acme.com", "hash", "Alice"));
        AppUser bob = userRepository.saveAndFlush(new AppUser("bob@acme.com", "hash", "Bob"));
        membershipRepository.saveAndFlush(new Membership(alice.getId(), acme.getId(), Role.OWNER));
        membershipRepository.saveAndFlush(new Membership(alice.getId(), globex.getId(), Role.AGENT));
        // Bob 也在 Acme：如果查询漏了 user_id 条件，Alice 的结果里就会混进 Bob
        membershipRepository.saveAndFlush(new Membership(bob.getId(), acme.getId(), Role.AGENT));

        assertThat(membershipRepository.findByIdUserId(alice.getId()))
                .extracting(Membership::getId)
                .containsExactlyInAnyOrder(
                        new MembershipId(alice.getId(), acme.getId()), new MembershipId(alice.getId(), globex.getId()));
        assertThat(membershipRepository.findByIdUserId(bob.getId()))
                .extracting(Membership::getId)
                .containsExactly(new MembershipId(bob.getId(), acme.getId()));
    }
}

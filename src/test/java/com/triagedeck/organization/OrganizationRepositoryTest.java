package com.triagedeck.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triagedeck.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class OrganizationRepositoryTest {

    @Autowired
    OrganizationRepository repository;

    @Test
    void saveGeneratesUuidV7IdInDatabase() {
        Organization saved = repository.saveAndFlush(new Organization("Acme", "acme"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId().version()).isEqualTo(7);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void saveRejectsDuplicateSlug() {
        repository.saveAndFlush(new Organization("Acme", "acme"));
        Organization duplicate = new Organization("duplicate", "acme");
        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

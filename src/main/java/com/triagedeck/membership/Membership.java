package com.triagedeck.membership;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "membership")
@Getter
public class Membership implements Persistable<MembershipId> {
    @EmbeddedId
    private MembershipId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Membership() {}

    public Membership(UUID userId, UUID orgId, Role role) {
        this.id = new MembershipId(userId, orgId);
        this.role = role;
    }

    @Override
    public boolean isNew() {
        return createdAt == null;
    }
}

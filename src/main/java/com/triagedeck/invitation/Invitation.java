package com.triagedeck.invitation;

import com.triagedeck.membership.Role;
import com.triagedeck.user.AppUser;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "invitation")
@Getter
public class Invitation {
    @Id
    @Generated
    private UUID id;

    @Column(nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private String tokenHash;

    @Column(nullable = false)
    private UUID invitedBy;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant acceptedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Invitation() {}

    public Invitation(UUID orgId, String email, Role role, String tokenHash, UUID invitedBy, Instant expiresAt) {
        this.orgId = orgId;
        this.email = AppUser.normalizeEmail(email);
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
    }

    /** 邀请只能用一次，接受过就不能再用。 */
    public boolean isAccepted() {
        return acceptedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void markAccepted(Instant now) {
        this.acceptedAt = now;
    }
}

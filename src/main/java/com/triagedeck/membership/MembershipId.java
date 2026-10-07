package com.triagedeck.membership;

import jakarta.persistence.Embeddable;
import java.util.UUID;

@Embeddable
public record MembershipId(UUID userId, UUID orgId) {}

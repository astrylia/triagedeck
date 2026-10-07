package com.triagedeck.membership;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {
    List<Membership> findByIdUserId(UUID userId);
}

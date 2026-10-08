package com.triagedeck.invitation;

import com.triagedeck.membership.MembershipService;
import com.triagedeck.membership.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {

    /** 邀请链接的有效期。 */
    static final Duration VALID_FOR = Duration.ofDays(7);

    private final InvitationRepository invitationRepository;
    private final MembershipService membershipService;

    public InvitationService(InvitationRepository invitationRepository, MembershipService membershipService) {
        this.invitationRepository = invitationRepository;
        this.membershipService = membershipService;
    }

    /**
     * 创建一个邀请，返回保存好的邀请和原始 token。
     * 不是成员时 404（和查看组织一样），是成员但角色不够时 403。
     */
    @Transactional
    public CreatedInvitation create(UUID inviterId, UUID orgId, CreateInvitationRequest request) {
        membershipService.requireRole(inviterId, orgId, Role.OWNER, Role.ADMIN);
        String token = InvitationTokens.generate();
        String tokenHash = InvitationTokens.hash(token);
        Instant expiresAt = Instant.now().plus(VALID_FOR);
        Invitation invitation = new Invitation(orgId, request.email(), request.role(), tokenHash, inviterId, expiresAt);
        invitationRepository.save(invitation);
        return new CreatedInvitation(invitation, token);
    }
}

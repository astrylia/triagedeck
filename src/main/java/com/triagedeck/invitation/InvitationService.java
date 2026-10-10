package com.triagedeck.invitation;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.MembershipId;
import com.triagedeck.membership.MembershipRepository;
import com.triagedeck.membership.MembershipService;
import com.triagedeck.membership.Role;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {

    /** 邀请链接的有效期。 */
    static final Duration VALID_FOR = Duration.ofDays(7);

    private final InvitationRepository invitationRepository;
    private final MembershipService membershipService;
    private final MembershipRepository membershipRepository;
    private final AppUserRepository appUserRepository;

    public InvitationService(
            InvitationRepository invitationRepository,
            MembershipService membershipService,
            MembershipRepository membershipRepository,
            AppUserRepository appUserRepository) {
        this.invitationRepository = invitationRepository;
        this.membershipService = membershipService;
        this.membershipRepository = membershipRepository;
        this.appUserRepository = appUserRepository;
    }

    /**
     * 创建一个邀请，返回保存好的邀请和原始 token。
     * 不是成员时 404（和查看组织一样），是成员但角色不够时 403。
     */
    @Transactional
    public CreatedInvitation create(UUID inviterId, UUID orgId, CreateInvitationRequest request) {
        membershipService.requireRole(inviterId, orgId, Role.OWNER, Role.ADMIN);
        String token = SecureTokens.generate();
        String tokenHash = SecureTokens.hash(token);
        Instant expiresAt = Instant.now().plus(VALID_FOR);
        Invitation invitation = new Invitation(orgId, request.email(), request.role(), tokenHash, inviterId, expiresAt);
        invitationRepository.save(invitation);
        return new CreatedInvitation(invitation, token);
    }

    /**
     * 当前用户凭 token 接受邀请，成为组织成员，返回新建的成员关系。
     *
     * <p>检查顺序：token 存在 → 当前用户的邮箱就是被邀请的邮箱 → 没用过 → 没过期 → 还不是成员。
     * 只核对邮箱就够了：注册时必须先打开发往这个邮箱的注册链接，账号的邮箱一定属于本人，
     * 别人没法先用你的邮箱注册、再冒领发给你的邀请。
     * 先核对邮箱，再告诉对方"用过了 / 过期了"：别人捡到链接，也打听不到这个邀请的状态。
     */
    @Transactional
    public Membership accept(UUID userId, String token) {
        Invitation invitation = invitationRepository
                .findByTokenHash(SecureTokens.hash(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_NOT_FOUND));
        AppUser user =
                appUserRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.getEmail().equals(invitation.getEmail())) {
            throw new BusinessException(ErrorCode.INVITATION_EMAIL_MISMATCH);
        }
        Instant now = Instant.now();
        if (invitation.isAccepted()) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_USED);
        }
        if (invitation.isExpiredAt(now)) {
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
        if (membershipRepository.existsById(new MembershipId(userId, invitation.getOrgId()))) {
            throw new BusinessException(ErrorCode.ALREADY_MEMBER);
        }
        Membership saved;
        try {
            // 同一个人同时点了两次：两个请求都通过了上面的检查，第二条 INSERT 会撞成员关系的主键 (user_id, org_id)。
            // saveAndFlush 让这个冲突在 try 里就抛出来，转成 409，而不是在提交事务时变成 500
            saved = membershipRepository.saveAndFlush(
                    new Membership(userId, invitation.getOrgId(), invitation.getRole()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_MEMBER);
        }
        // 不用再调 save：invitation 是在这个事务里查出来的，提交时 JPA 会发现它被改过，自动 UPDATE
        invitation.markAccepted(now);
        return saved;
    }
}

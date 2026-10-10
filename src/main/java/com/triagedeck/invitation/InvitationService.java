package com.triagedeck.invitation;

import com.triagedeck.auth.AuthService;
import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.MembershipId;
import com.triagedeck.membership.MembershipRepository;
import com.triagedeck.membership.MembershipService;
import com.triagedeck.membership.Role;
import com.triagedeck.organization.OrganizationRepository;
import com.triagedeck.user.AppUser;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvitationService {

    /** 邀请链接的有效期。 */
    static final Duration VALID_FOR = Duration.ofDays(7);

    private final InvitationRepository invitationRepository;
    private final MembershipService membershipService;
    private final MembershipRepository membershipRepository;
    private final OrganizationRepository organizationRepository;
    private final AuthService authService;
    private final InvitationEmailSender emailSender;
    private final InvitationProperties properties;
    private final TransactionTemplate transactionTemplate;

    public InvitationService(
            InvitationRepository invitationRepository,
            MembershipService membershipService,
            MembershipRepository membershipRepository,
            OrganizationRepository organizationRepository,
            AuthService authService,
            InvitationEmailSender emailSender,
            InvitationProperties properties,
            TransactionTemplate transactionTemplate) {
        this.invitationRepository = invitationRepository;
        this.membershipService = membershipService;
        this.membershipRepository = membershipRepository;
        this.organizationRepository = organizationRepository;
        this.authService = authService;
        this.emailSender = emailSender;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 创建一个邀请，并把邀请链接发到被邀请的邮箱。返回保存好的邀请（不含 token）。
     * 不是成员时 404（和查看组织一样），是成员但角色不够时 403。
     *
     * <p>链接只发到邮箱、不返回给邀请人：能打开链接就证明是邮箱的主人，打开它就能加入并登录（见 accept）。
     * 如果邀请人也能拿到链接，他就能冒充被邀请的人。
     *
     * <p>不加 @Transactional：写库只有一条 INSERT，save 自己会开事务。邮件在 INSERT 提交之后才发，
     * 不会出现"邮件发出去了、邀请却没存进数据库"。
     */
    public Invitation create(UUID inviterId, UUID orgId, CreateInvitationRequest request) {
        membershipService.requireRole(inviterId, orgId, Role.OWNER, Role.ADMIN);
        String orgName = organizationRepository
                .findById(orgId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORG_NOT_FOUND))
                .getName();
        String token = SecureTokens.generate();
        Instant expiresAt = Instant.now().plus(VALID_FOR);
        Invitation invitation = invitationRepository.save(
                new Invitation(orgId, request.email(), request.role(), SecureTokens.hash(token), inviterId, expiresAt));
        emailSender.sendInvitation(
                invitation.getEmail(),
                orgName,
                invitation.getRole(),
                properties.url() + "?token=" + token,
                VALID_FOR.toDays());
        return invitation;
    }

    /**
     * 凭邀请链接加入组织，返回新建的成员关系。不需要先登录：链接只发到被邀请的邮箱，能打开就证明是邮箱的主人，
     * 所以直接按邀请里的邮箱找到账号，没有账号就建一个。之后由控制器给这个账号签发 token，等于顺便登录。
     *
     * <p>不加 @Transactional：建账号和加入组织各用一个事务。建账号时如果撞上邮箱的唯一约束（同一个新邮箱的两个请求同时到达），
     * 所在的事务就不能再用了，所以它要在加入组织的事务外面做。万一账号建好了、加入组织失败，邀请没被标记为已使用，再点一次链接即可。
     */
    public Membership accept(String token) {
        // 先确认邀请还能用，再建账号：不然用一个过期的邀请也能建出账号
        Invitation invitation = findByToken(token);
        requireUsable(invitation, Instant.now());
        AppUser user = authService.findOrCreateUser(invitation.getEmail());
        // join 是同一个类里的私有方法，没法用 @Transactional（Spring 的事务要经过代理才会开启），
        // 所以用 TransactionTemplate 显式开一个事务：标记邀请已使用靠的是事务提交时自动 UPDATE
        return transactionTemplate.execute(status -> join(user.getId(), token));
    }

    private Membership join(UUID userId, String token) {
        // 在事务里重新查一次邀请：上面检查之后，可能有另一个请求刚用掉了它
        Invitation invitation = findByToken(token);
        Instant now = Instant.now();
        requireUsable(invitation, now);
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

    private Invitation findByToken(String token) {
        return invitationRepository
                .findByTokenHash(SecureTokens.hash(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_NOT_FOUND));
    }

    /** 用过的邀请抛 INVITATION_ALREADY_USED（409），过期的抛 INVITATION_EXPIRED（410）。 */
    private static void requireUsable(Invitation invitation, Instant now) {
        if (invitation.isAccepted()) {
            throw new BusinessException(ErrorCode.INVITATION_ALREADY_USED);
        }
        if (invitation.isExpiredAt(now)) {
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
    }
}

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
import com.triagedeck.user.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvitationService {

    /** 邀请链接的有效期。 */
    static final Duration VALID_FOR = Duration.ofDays(7);

    private final InvitationRepository invitationRepository;
    private final MembershipService membershipService;
    private final MembershipRepository membershipRepository;
    private final AppUserRepository appUserRepository;
    private final OrganizationRepository organizationRepository;
    private final AuthService authService;
    private final InvitationEmailSender emailSender;
    private final InvitationProperties properties;
    private final TransactionTemplate transactionTemplate;

    public InvitationService(
            InvitationRepository invitationRepository,
            MembershipService membershipService,
            MembershipRepository membershipRepository,
            AppUserRepository appUserRepository,
            OrganizationRepository organizationRepository,
            AuthService authService,
            InvitationEmailSender emailSender,
            InvitationProperties properties,
            TransactionTemplate transactionTemplate) {
        this.invitationRepository = invitationRepository;
        this.membershipService = membershipService;
        this.membershipRepository = membershipRepository;
        this.appUserRepository = appUserRepository;
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
     * <p>链接只发到邮箱、不返回给邀请人：能打开链接就证明是邮箱的主人，没账号的人可以直接在邀请页设密码加入
     * （见 signUpAndAccept）。如果邀请人也能拿到链接，他就能替别人的邮箱建账号。
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
     * 当前用户凭 token 接受邀请，成为组织成员，返回新建的成员关系。
     *
     * <p>检查顺序：token 存在 → 当前用户的邮箱就是被邀请的邮箱 → 没用过 → 没过期 → 还不是成员。
     * 链接只发到被邀请的邮箱，但邮件可能被转发，所以仍然核对登录账号的邮箱。账号的邮箱一定属于本人
     * （注册、凭邀请建账号都要先打开发往这个邮箱的链接），别人没法冒领发给你的邀请。
     * 先核对邮箱，再告诉对方"用过了 / 过期了"：别人捡到链接，也打听不到这个邀请的状态。
     */
    @Transactional
    public Membership accept(UUID userId, String token) {
        Invitation invitation = findByToken(token);
        AppUser user =
                appUserRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (!user.getEmail().equals(invitation.getEmail())) {
            throw new BusinessException(ErrorCode.INVITATION_EMAIL_MISMATCH);
        }
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

    /**
     * 还没有账号的人凭邀请链接建账号并加入组织，不用先走注册。链接是系统发到这个邮箱的，能打开就证明邮箱是他的，
     * 所以账号的邮箱直接取邀请里的邮箱。邮箱已经注册过时返回 409 EMAIL_ALREADY_USED，前端提示他登录后再接受。
     *
     * <p>不加 @Transactional：建账号（AuthService.createAccount，Argon2 在事务外算）和加入组织各用一个事务。
     * 万一账号建好了、加入组织失败，邀请还没被标记为已使用，他登录后照样可以接受，不会卡住。
     */
    public SignUpWithInvitationResponse signUpAndAccept(SignUpWithInvitationRequest request) {
        // 先确认邀请还能用，再建账号：不然用一个过期的邀请也能建出账号，再告诉他"邀请过期了"
        Invitation invitation = findByToken(request.token());
        requireUsable(invitation, Instant.now());
        AppUser user = authService.createAccount(invitation.getEmail(), request.password(), request.name());
        // 直接调用同一个类里的 accept，它上面的 @Transactional 不生效（Spring 的事务要经过代理才会开启），
        // 所以用 TransactionTemplate 显式开一个事务：标记邀请已使用靠的是事务提交时自动 UPDATE
        Membership membership = transactionTemplate.execute(status -> accept(user.getId(), request.token()));
        return SignUpWithInvitationResponse.from(user, membership);
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

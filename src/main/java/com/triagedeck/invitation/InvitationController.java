package com.triagedeck.invitation;

import com.triagedeck.ratelimit.RateLimitProperties;
import com.triagedeck.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvitationController {

    private final InvitationService invitationService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public InvitationController(
            InvitationService invitationService, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.invitationService = invitationService;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    /** 邀请别人加入组织，系统把邀请链接发到对方邮箱。只有 OWNER 和 ADMIN 能邀请。 */
    @PostMapping("/api/orgs/{orgId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateInvitationRequest request) {
        UUID currentUserId = UUID.fromString(jwt.getSubject());
        return InvitationResponse.from(invitationService.create(currentUserId, orgId, request));
    }

    /** 已经有账号的人：凭邀请链接里的 token 加入组织。要先登录，并且登录的邮箱必须是被邀请的邮箱。 */
    @PostMapping("/api/invitations/accept")
    public AcceptInvitationResponse accept(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AcceptInvitationRequest request) {
        UUID currentUserId = UUID.fromString(jwt.getSubject());
        return AcceptInvitationResponse.from(invitationService.accept(currentUserId, request.token()));
    }

    /** 还没有账号的人：在邀请页设密码，建账号并加入组织。不需要登录，邮箱由邀请决定。 */
    @PostMapping("/api/invitations/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignUpWithInvitationResponse signUp(
            @Valid @RequestBody SignUpWithInvitationRequest request, HttpServletRequest http) {
        // 和注册共用一个按 IP 的限额：两个接口都会建账号、都要算一次 Argon2
        rateLimiter.check("register:ip:" + http.getRemoteAddr(), limits.registerPerIp());
        return invitationService.signUpAndAccept(request);
    }
}

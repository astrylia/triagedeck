package com.triagedeck.invitation;

import com.triagedeck.auth.AuthService;
import com.triagedeck.membership.Membership;
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
    private final AuthService authService;

    public InvitationController(InvitationService invitationService, AuthService authService) {
        this.invitationService = invitationService;
        this.authService = authService;
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

    /** 凭邀请链接里的 token 加入组织，同时登录。不需要先登录，没有账号时自动建一个。 */
    @PostMapping("/api/invitations/accept")
    public AcceptInvitationResponse accept(@Valid @RequestBody AcceptInvitationRequest request) {
        Membership membership = invitationService.accept(request.token());
        return AcceptInvitationResponse.from(
                membership, authService.issueTokens(membership.getId().userId()));
    }
}

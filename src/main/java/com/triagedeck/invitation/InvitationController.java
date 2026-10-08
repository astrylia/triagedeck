package com.triagedeck.invitation;

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

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    /** 邀请别人加入组织。只有 OWNER 和 ADMIN 能邀请。 */
    @PostMapping("/api/orgs/{orgId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateInvitationRequest request) {
        UUID currentUserId = UUID.fromString(jwt.getSubject());
        return InvitationResponse.from(invitationService.create(currentUserId, orgId, request));
    }
}

package com.triagedeck.invitation;

import com.triagedeck.membership.Role;
import java.time.Instant;
import java.util.UUID;

/** token 只在创建时返回这一次，前端用它拼出邀请链接发给对方。 */
public record InvitationResponse(UUID id, String email, Role role, Instant expiresAt, String token) {

    public static InvitationResponse from(CreatedInvitation created) {
        Invitation invitation = created.invitation();
        return new InvitationResponse(
                invitation.getId(),
                invitation.getEmail(),
                invitation.getRole(),
                invitation.getExpiresAt(),
                created.token());
    }
}

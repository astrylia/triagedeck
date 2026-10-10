package com.triagedeck.invitation;

import com.triagedeck.membership.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * 创建好的邀请。不包含 token：邀请链接由系统直接发到被邀请的邮箱，邀请人拿不到。
 * 这样能打开链接就证明是邮箱的主人，新人可以直接在邀请页设密码加入。
 */
public record InvitationResponse(UUID id, String email, Role role, Instant expiresAt) {

    public static InvitationResponse from(Invitation invitation) {
        return new InvitationResponse(
                invitation.getId(), invitation.getEmail(), invitation.getRole(), invitation.getExpiresAt());
    }
}

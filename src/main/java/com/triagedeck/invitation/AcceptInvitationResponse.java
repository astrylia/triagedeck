package com.triagedeck.invitation;

import com.triagedeck.auth.TokenResponse;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.Role;
import java.util.UUID;

/** 加入了哪个组织、是什么角色，以及登录用的 token。前端存好 token，再跳到 orgId 对应的组织页面。 */
public record AcceptInvitationResponse(UUID orgId, Role role, TokenResponse tokens) {

    public static AcceptInvitationResponse from(Membership membership, TokenResponse tokens) {
        return new AcceptInvitationResponse(membership.getId().orgId(), membership.getRole(), tokens);
    }
}

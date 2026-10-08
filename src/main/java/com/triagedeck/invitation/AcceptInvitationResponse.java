package com.triagedeck.invitation;

import com.triagedeck.membership.Membership;
import com.triagedeck.membership.Role;
import java.util.UUID;

/** 加入了哪个组织、是什么角色。前端拿 orgId 跳到组织页面。 */
public record AcceptInvitationResponse(UUID orgId, Role role) {

    public static AcceptInvitationResponse from(Membership membership) {
        return new AcceptInvitationResponse(membership.getId().orgId(), membership.getRole());
    }
}

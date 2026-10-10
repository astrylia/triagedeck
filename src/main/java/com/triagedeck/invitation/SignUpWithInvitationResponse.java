package com.triagedeck.invitation;

import com.triagedeck.auth.UserResponse;
import com.triagedeck.membership.Membership;
import com.triagedeck.membership.Role;
import com.triagedeck.user.AppUser;
import java.util.UUID;

/** 建好的账号，以及加入了哪个组织、是什么角色。前端用 user.email 和刚设的密码登录，再跳到 orgId 对应的组织页面。 */
public record SignUpWithInvitationResponse(UserResponse user, UUID orgId, Role role) {

    public static SignUpWithInvitationResponse from(AppUser user, Membership membership) {
        return new SignUpWithInvitationResponse(
                UserResponse.from(user), membership.getId().orgId(), membership.getRole());
    }
}

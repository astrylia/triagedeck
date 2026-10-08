package com.triagedeck.invitation;

import com.triagedeck.membership.Role;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 邀请别人加入组织：对方的邮箱，以及加入后的角色。 */
public record CreateInvitationRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull Role role) {

    /** OWNER 只能在创建组织时产生，不能通过邀请得到。 */
    @AssertTrue(message = "cannot invite someone as OWNER")
    public boolean isRoleInvitable() {
        return role != Role.OWNER;
    }
}

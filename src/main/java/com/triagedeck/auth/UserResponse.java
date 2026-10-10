package com.triagedeck.auth;

import com.triagedeck.user.AppUser;
import java.util.UUID;

/** 返回给前端的用户信息。不直接返回 AppUser 实体：实体以后加的字段会悄悄出现在响应里。 */
public record UserResponse(UUID id, String email, String name) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName());
    }
}

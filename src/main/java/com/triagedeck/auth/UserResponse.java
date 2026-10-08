package com.triagedeck.auth;

import com.triagedeck.user.AppUser;
import java.util.UUID;

/** 返回给前端的用户信息。不能直接返回 AppUser 实体，否则 passwordHash 也会被序列化出去。 */
public record UserResponse(UUID id, String email, String name, boolean emailVerified) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.isEmailVerified());
    }
}

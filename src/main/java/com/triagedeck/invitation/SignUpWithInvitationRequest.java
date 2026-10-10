package com.triagedeck.invitation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 还没有账号的人凭邀请链接建账号并加入组织。邮箱由邀请决定，所以请求里没有 email。 */
public record SignUpWithInvitationRequest(
        @NotBlank String token,
        // 长度规则和 RegisterRequest 一样，原因见那里的注释
        @NotBlank @Size(min = 15, max = 128) String password,
        @NotBlank @Size(max = 100) String name) {}

package com.triagedeck.invitation;

import jakarta.validation.constraints.NotBlank;

/** 接受邀请：前端从邀请链接里取出 token 发过来。 */
public record AcceptInvitationRequest(@NotBlank String token) {}

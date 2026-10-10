package com.triagedeck.auth;

import jakarta.validation.constraints.NotBlank;

/** 登录：前端从登录链接里取出 token 发过来。 */
public record LoginRequest(@NotBlank String token) {}

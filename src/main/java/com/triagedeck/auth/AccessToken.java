package com.triagedeck.auth;

/** 签好名的 access token 和它的有效秒数。 */
public record AccessToken(String accessToken, long expiresIn) {}

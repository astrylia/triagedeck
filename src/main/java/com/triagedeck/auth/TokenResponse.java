package com.triagedeck.auth;

import com.triagedeck.auth.token.AccessToken;

/** 登录和刷新的响应。access token 放在 Authorization 头里调接口；它过期后用 refresh token 换一对新的。 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken) {

    public static TokenResponse of(AccessToken accessToken, String refreshToken) {
        return new TokenResponse(accessToken.accessToken(), "Bearer", accessToken.expiresIn(), refreshToken);
    }
}

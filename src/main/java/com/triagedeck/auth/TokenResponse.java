package com.triagedeck.auth;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

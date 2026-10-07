package com.triagedeck.common;

import org.springframework.http.HttpStatus;

/**
 * 所有错误码集中定义在这里。code（枚举名）是给前端判断用的稳定标识，一旦发布就不要改名；
 * message 是给人看的默认说明，可以随时改。
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    // 邮箱不存在和密码错误都用这一个，不告诉调用方是哪一种，避免被用来探测哪些邮箱注册过
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "Email already registered"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}

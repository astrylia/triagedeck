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
    // 没带 access token，或者 token 无效、已过期。前端收到它可以先用 refresh token 换一个新的再重试
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Missing, invalid or expired access token"),
    // 不存在、过期、已用过、已退出登录都用这一个：前端的处理都一样，让用户重新登录
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh token is invalid or expired; please log in again"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "Email already registered"),
    // 验证链接不存在或已过期：前端的处理都一样，让用户重新发送验证邮件
    INVALID_VERIFICATION_TOKEN(HttpStatus.BAD_REQUEST, "Verification link is invalid or expired; request a new one"),
    // 密码正确，但邮箱还没验证：前端引导用户去收信，或者重新发送验证邮件
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Please verify your email address first"),
    // 组织不存在、或者当前用户不是它的成员，都用这一个：不让外人确认某个组织 id 是否存在
    ORG_NOT_FOUND(HttpStatus.NOT_FOUND, "Organization not found"),
    ORG_SLUG_ALREADY_USED(HttpStatus.CONFLICT, "Organization slug already taken"),
    // 是组织成员，但角色不够做这件事（比如 AGENT 想邀请别人）
    INSUFFICIENT_ROLE(HttpStatus.FORBIDDEN, "Your role in this organization does not allow this action"),
    ALREADY_MEMBER(HttpStatus.CONFLICT, "You are already a member of this organization"),
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Invitation not found"),
    // 邀请绑定了邮箱：登录的账号不是被邀请的那个邮箱
    INVITATION_EMAIL_MISMATCH(HttpStatus.FORBIDDEN, "This invitation was sent to a different email address"),
    INVITATION_ALREADY_USED(HttpStatus.CONFLICT, "Invitation has already been used"),
    // 410 Gone：这个东西以前有效，现在永久失效了
    INVITATION_EXPIRED(HttpStatus.GONE, "Invitation has expired"),
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

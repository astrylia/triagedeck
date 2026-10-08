package com.triagedeck.auth;

/**
 * 事件：需要给这个邮箱发一封验证邮件。
 * 由 EmailVerificationService 在事务里发布，VerificationEmailSender 在事务提交之后才真正发信。
 */
public record VerificationEmailRequested(String email, String name, String token) {}

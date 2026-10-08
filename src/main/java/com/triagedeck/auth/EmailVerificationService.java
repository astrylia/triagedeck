package com.triagedeck.auth;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邮箱验证：注册后给用户发一个链接，用户点开说明这个邮箱确实是他的。
 *
 * <p>验证 token 和邀请、refresh token 一样：32 字节随机数，数据库只存 SHA-256。
 * 同一个链接点两次不报错（已经验证过就直接成功），因为用户重复点链接很常见。
 */
@Service
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final AppUserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final MailSettings settings;

    public EmailVerificationService(
            EmailVerificationTokenRepository tokenRepository,
            AppUserRepository userRepository,
            ApplicationEventPublisher events,
            MailSettings settings) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.events = events;
        this.settings = settings;
    }

    /**
     * 生成验证 token 并安排发信。必须在调用方的事务里执行：邮件在事务提交之后才发出。
     */
    @Transactional
    public void sendVerificationEmail(AppUser user) {
        String token = SecureTokens.generate();
        Instant expiresAt = Instant.now().plus(settings.verificationTokenTtl());
        tokenRepository.save(new EmailVerificationToken(user.getId(), SecureTokens.hash(token), expiresAt));
        events.publishEvent(new VerificationEmailRequested(user.getEmail(), user.getName(), token));
    }

    /**
     * 登录后的"重新发送验证邮件"。已验证过返回 409；距上次发送不到冷却时间返回 429。
     */
    @Transactional
    public void resend(UUID userId) {
        AppUser user =
                userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.isEmailVerified()) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_VERIFIED);
        }
        Instant earliestNext = Instant.now().minus(settings.resendCooldown());
        tokenRepository
                .findFirstByUserIdOrderByCreatedAtDesc(userId)
                .filter(last -> last.getCreatedAt().isAfter(earliestNext))
                .ifPresent(last -> {
                    throw new BusinessException(ErrorCode.VERIFICATION_EMAIL_TOO_FREQUENT);
                });
        sendVerificationEmail(user);
    }

    /**
     * 用户点开邮件里的链接，前端把 token 交给这里。不存在和已过期都返回同一个错误，前端的处理一样：让用户重新发送。
     */
    @Transactional
    public void verify(String token) {
        Instant now = Instant.now();
        EmailVerificationToken verification = tokenRepository
                .findByTokenHash(SecureTokens.hash(token))
                .filter(t -> !t.isExpiredAt(now))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_VERIFICATION_TOKEN));
        AppUser user = userRepository
                .findById(verification.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        // 已经验证过就保持原来的时间；user 是这个事务里查出来的，改了字段提交时会自动 UPDATE
        user.markEmailVerified(now);
    }
}

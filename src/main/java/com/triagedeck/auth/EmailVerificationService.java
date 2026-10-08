package com.triagedeck.auth;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
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
     * 重新发送验证邮件。没验证就登录不了，所以这个接口不需要登录，只凭邮箱。
     *
     * <p>不管邮箱有没有注册、是否已经验证、是否发得太频繁，调用方看到的结果都一样（什么都不返回），
     * 否则别人可以用这个接口探测某个邮箱有没有注册过。只有"注册了、没验证、距上次发送超过冷却时间"才真的发信。
     *
     * <p>@Async：整个方法（查用户、建 token、发信）都在后台线程执行，接口立刻返回。
     * 这样连响应时间都看不出差别：邮箱存在时要查库、写库、发信，不存在时什么都不做，如果同步执行，前者明显更慢。
     */
    @Async
    @Transactional
    public void resend(String email) {
        Instant earliestNext = Instant.now().minus(settings.resendCooldown());
        userRepository
                .findByEmail(AppUser.normalizeEmail(email))
                .filter(user -> !user.isEmailVerified())
                .filter(user -> tokenRepository
                        .findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                        .map(last -> !last.getCreatedAt().isAfter(earliestNext))
                        .orElse(true))
                .ifPresent(this::sendVerificationEmail);
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

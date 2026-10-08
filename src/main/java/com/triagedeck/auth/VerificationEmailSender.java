package com.triagedeck.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 真正发出验证邮件。
 *
 * <p>为什么用事件 + @TransactionalEventListener，而不是在注册方法里直接发：
 * 它默认在事务<b>提交之后</b>才执行。如果注册在最后一刻失败回滚了，邮件就不会发出去
 * （否则用户会收到一个指向不存在账号的链接）；发信慢也不会拖着数据库事务不放。
 */
@Component
public class VerificationEmailSender {

    private static final Logger log = LoggerFactory.getLogger(VerificationEmailSender.class);

    private final JavaMailSender mailSender;
    private final MailSettings settings;

    public VerificationEmailSender(JavaMailSender mailSender, MailSettings settings) {
        this.mailSender = mailSender;
        this.settings = settings;
    }

    @TransactionalEventListener
    public void send(VerificationEmailRequested event) {
        String link = UriComponentsBuilder.fromUriString(settings.verifyEmailUrl())
                .queryParam("token", event.token())
                .toUriString();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(event.email());
        message.setSubject("Verify your TriageDeck email address");
        message.setText("""
                Hi %s,

                Please confirm your email address by opening this link:

                %s

                The link expires in %d hours. If you didn't sign up for TriageDeck, you can ignore this email.
                """.formatted(
                        event.name(), link, settings.verificationTokenTtl().toHours()));
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // 发信失败不影响注册结果：账号已经建好了，用户可以在登录后点"重新发送"
            log.warn("Failed to send verification email to {}", event.email(), e);
        }
    }
}

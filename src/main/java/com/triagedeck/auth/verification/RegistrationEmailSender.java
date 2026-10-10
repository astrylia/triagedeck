package com.triagedeck.auth.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 注册相关的两种邮件：给新邮箱发注册链接；给已经注册过的邮箱发"你已经有账号了"的提醒。
 *
 * <p>本身不是异步的：调用它的 RegistrationLinkService.sendLink 已经在后台线程里跑。
 */
@Component
public class RegistrationEmailSender {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEmailSender.class);

    private final JavaMailSender mailSender;
    private final MailSettings settings;

    public RegistrationEmailSender(JavaMailSender mailSender, MailSettings settings) {
        this.mailSender = mailSender;
        this.settings = settings;
    }

    public void sendLink(String email, String link, long ttlMinutes) {
        send(email, "Finish creating your TriageDeck account", """
                Open this link to set your password and finish creating your TriageDeck account:

                %s

                It expires in %d minutes. If you didn't try to sign up for TriageDeck, you can ignore this email.
                """.formatted(link, ttlMinutes));
    }

    /**
     * 有人拿一个已注册的邮箱来要注册链接。接口那边看不出区别（照样 204），真正的邮箱主人会收到这封提醒，
     * 知道自己已经有账号、直接去登录就行。
     */
    public void sendAlreadyRegistered(String email) {
        send(email, "You already have a TriageDeck account", """
                Someone (hopefully you) tried to sign up for TriageDeck with this email address,
                but an account with this address already exists. You can log in with your existing password.

                If this wasn't you, you can ignore this email. Your account has not been changed.
                """);
    }

    private void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // 接口早就返回 204 了，这里只能记日志；用户收不到信，过了冷却时间可以再要一次
            log.warn("Failed to send registration email to {}", to, e);
        }
    }
}

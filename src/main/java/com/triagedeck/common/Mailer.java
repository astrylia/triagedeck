package com.triagedeck.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 发一封纯文本邮件，发件人统一取 triagedeck.mail.from。注册邮件和邀请邮件都用它。
 *
 * <p>发信失败只记日志、不抛异常：调用方都在后台线程里发信，接口早就返回了，抛出去也没人能处理。
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final JavaMailSender mailSender;
    private final MailSettings settings;

    public Mailer(JavaMailSender mailSender, MailSettings settings) {
        this.mailSender = mailSender;
        this.settings = settings;
    }

    public void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("Failed to send email to {}", to, e);
        }
    }
}

package com.triagedeck.auth.verification;

import com.triagedeck.common.Mailer;
import org.springframework.stereotype.Component;

/**
 * 注册相关的两种邮件：给新邮箱发注册链接；给已经注册过的邮箱发"你已经有账号了"的提醒。
 *
 * <p>本身不是异步的：调用它的 RegistrationLinkService.sendLink 已经在后台线程里跑。
 */
@Component
public class RegistrationEmailSender {

    private final Mailer mailer;

    public RegistrationEmailSender(Mailer mailer) {
        this.mailer = mailer;
    }

    public void sendLink(String email, String link, long ttlMinutes) {
        mailer.send(email, "Finish creating your TriageDeck account", """
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
        mailer.send(email, "You already have a TriageDeck account", """
                Someone (hopefully you) tried to sign up for TriageDeck with this email address,
                but an account with this address already exists. You can log in with your existing password.

                If this wasn't you, you can ignore this email. Your account has not been changed.
                """);
    }
}

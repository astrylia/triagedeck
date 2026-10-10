package com.triagedeck.auth.link;

import com.triagedeck.common.Mailer;
import org.springframework.stereotype.Component;

/**
 * 登录链接邮件。新邮箱和已有账号的邮箱收到的是同一封：打开链接就登录，没有账号时自动建一个。
 *
 * <p>本身不是异步的：调用它的 LoginLinkService.sendLink 已经在后台线程里跑。
 */
@Component
public class LoginLinkEmailSender {

    private final Mailer mailer;

    public LoginLinkEmailSender(Mailer mailer) {
        this.mailer = mailer;
    }

    public void sendLink(String email, String link, long ttlMinutes) {
        mailer.send(email, "Your TriageDeck sign-in link", """
                Open this link to sign in to TriageDeck:

                %s

                It expires in %d minutes and can be used once. If you didn't try to sign in, you can ignore this email.
                """.formatted(link, ttlMinutes));
    }
}

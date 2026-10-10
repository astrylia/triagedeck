package com.triagedeck.invitation;

import com.triagedeck.common.Mailer;
import com.triagedeck.membership.Role;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 把邀请链接发到被邀请的邮箱。 */
@Component
public class InvitationEmailSender {

    private final Mailer mailer;

    public InvitationEmailSender(Mailer mailer) {
        this.mailer = mailer;
    }

    /**
     * @Async：在后台线程发信，创建邀请的接口不用等 SMTP 服务器。
     * 用到的数据（组织名等）都由调用方传进来，后台线程不查数据库。
     */
    @Async
    public void sendInvitation(String email, String orgName, Role role, String link, long validForDays) {
        mailer.send(email, "You're invited to join " + orgName + " on TriageDeck", """
                You've been invited to join %s on TriageDeck as %s.

                Open this link to accept the invitation:

                %s

                If you don't have a TriageDeck account yet, you can set a password on that page and join right away.
                The link expires in %d days. If you weren't expecting this invitation, you can ignore this email.
                """.formatted(
                        orgName, role, link, validForDays));
    }
}

package com.triagedeck.auth.link;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.user.AppUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 登录链接：没有密码，用户填邮箱，系统发一封带一次性链接的邮件，打开链接就登录。
 * 能打开发到这个邮箱的链接，就证明邮箱是他的。邮箱还没有账号时，第一次打开链接就建账号，所以注册和登录是同一个流程。
 *
 * <p>链接里的 token 是 32 字节随机数（见 SecureTokens），不可能被猜中，所以不需要"最多试几次"这类防猜测逻辑。
 * Redis 里只存它的 SHA-256 哈希（key）和对应的邮箱（value），到期自动删除：拿到 Redis 数据的人也拼不出可用的链接。
 */
@Service
public class LoginLinkService {

    private final StringRedisTemplate redis;
    private final LoginLinkEmailSender emailSender;
    private final LoginLinkProperties properties;

    public LoginLinkService(
            StringRedisTemplate redis, LoginLinkEmailSender emailSender, LoginLinkProperties properties) {
        this.redis = redis;
        this.emailSender = emailSender;
        this.properties = properties;
    }

    /**
     * 给邮箱发登录链接。@Async：在后台线程里发信，接口立刻返回 204，不用等 SMTP。
     * 同一个邮箱在冷却时间内只发一次，防止有人用接口不停给别人发邮件。
     */
    @Async
    public void sendLink(String email) {
        String normalized = AppUser.normalizeEmail(email);
        // SET NX：只有冷却 key 不存在时才设置成功，并发的两个请求只有一个能发信
        Boolean first =
                redis.opsForValue().setIfAbsent("login-link-cooldown:" + normalized, "1", properties.resendCooldown());
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        String link = properties.url() + "?token=" + issueToken(normalized);
        emailSender.sendLink(normalized, link, properties.ttl().toMinutes());
    }

    /**
     * 生成一个登录 token 存进 Redis 并返回它。不发信、不检查冷却。
     * 正常流程由 sendLink 调用；测试也直接用它拿 token，省掉收信这一步。
     */
    public String issueToken(String email) {
        String token = SecureTokens.generate();
        redis.opsForValue().set(key(token), AppUser.normalizeEmail(email), properties.ttl());
        return token;
    }

    /**
     * 用掉 token，返回它对应的邮箱。token 不存在、过期、已用过都抛 INVALID_LOGIN_LINK。
     * GETDEL 是一条命令，查和删一起完成：同一个链接同时提交两次，只有一个请求拿得到邮箱。
     */
    public String consume(String token) {
        String email = redis.opsForValue().getAndDelete(key(token));
        if (email == null) {
            throw new BusinessException(ErrorCode.INVALID_LOGIN_LINK);
        }
        return email;
    }

    private static String key(String token) {
        return "login-link:" + SecureTokens.hash(token);
    }
}

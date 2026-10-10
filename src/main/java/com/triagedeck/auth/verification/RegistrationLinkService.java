package com.triagedeck.auth.verification;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.common.SecureTokens;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 注册链接：先证明邮箱是你的，再创建账号。用户填邮箱，系统发一封带一次性链接的邮件，点开链接设密码才建账号。
 *
 * <p>为什么不是"先建账号、再点邮件里的链接验证"：那样别人可以抢先用你的邮箱注册一个账号（密码是他设的），
 * 等你以后想注册时邮箱已被占用；如果他还拿这个账号接受了发给你邮箱的邀请，就混进了你的组织。
 * 现在没打开发往这个邮箱的链接就建不了账号，数据库里每个账号的邮箱都验证过。
 *
 * <p>链接里的 token 是 32 字节随机数（见 SecureTokens），不可能被猜中，所以不需要"最多试几次"这类防猜测逻辑。
 * Redis 里只存它的 SHA-256 哈希（key）和对应的邮箱（value），到期自动删除：拿到 Redis 数据的人也拼不出可用的链接。
 *
 * <p>打开链接只是显示前端的设密码页面，提交密码时才用掉 token。邮件安全扫描器会自动打开邮件里的链接，
 * 但不会提交表单，所以不会在用户点之前就把链接用掉。
 */
@Service
public class RegistrationLinkService {

    private final StringRedisTemplate redis;
    private final AppUserRepository userRepository;
    private final RegistrationEmailSender emailSender;
    private final RegistrationLinkProperties properties;

    public RegistrationLinkService(
            StringRedisTemplate redis,
            AppUserRepository userRepository,
            RegistrationEmailSender emailSender,
            RegistrationLinkProperties properties) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.emailSender = emailSender;
        this.properties = properties;
    }

    /**
     * 给邮箱发注册链接。接口不管结果都返回 204，所以调用方看不出邮箱有没有注册过、是不是还在冷却。
     *
     * <p>@Async：整个方法在后台线程执行，接口立刻返回。否则"已注册"（查库后发提醒）、"冷却中"（什么都不做）、
     * "新邮箱"（写 Redis、发信）三种情况的响应时间不一样，别人可以靠计时判断。
     *
     * <p>冷却对已注册的邮箱同样生效，不然别人可以用这个接口不停给一个已注册的用户发提醒邮件。
     */
    @Async
    public void sendLink(String email) {
        String normalized = AppUser.normalizeEmail(email);
        // SET NX：只有冷却 key 不存在时才设置成功，并发的两个请求只有一个能发信
        Boolean first = redis.opsForValue()
                .setIfAbsent("registration-link-cooldown:" + normalized, "1", properties.resendCooldown());
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        if (userRepository.existsByEmail(normalized)) {
            emailSender.sendAlreadyRegistered(normalized);
            return;
        }
        String link = properties.url() + "?token=" + issueToken(normalized);
        emailSender.sendLink(normalized, link, properties.ttl().toMinutes());
    }

    /**
     * 生成一个注册 token 存进 Redis 并返回它。不发信、不检查冷却。
     * 正常流程由 sendLink 调用；测试也直接用它拿 token，省掉收信这一步。
     *
     * <p>同一个邮箱之前发的链接不作废：每个链接都只能注册这一个邮箱，谁先用都一样，
     * 账号建好后其余链接再用会得到 409，到期后自动删除。
     */
    public String issueToken(String email) {
        String token = SecureTokens.generate();
        redis.opsForValue().set(key(token), AppUser.normalizeEmail(email), properties.ttl());
        return token;
    }

    /**
     * 查 token 对应的邮箱，不用掉 token。token 不存在、过期、已用过都抛 INVALID_REGISTRATION_LINK。
     * 注册时先拿邮箱建账号，建好才用掉 token，所以中途失败（比如密码不合格）链接还能继续用。
     */
    public String emailFor(String token) {
        String email = redis.opsForValue().get(key(token));
        if (email == null) {
            throw new BusinessException(ErrorCode.INVALID_REGISTRATION_LINK);
        }
        return email;
    }

    /** 用掉 token，之后这个链接就失效了。账号建好之后调用（见 AuthService.register）。 */
    public void consume(String token) {
        redis.delete(key(token));
    }

    private static String key(String token) {
        return "registration-link:" + SecureTokens.hash(token);
    }
}

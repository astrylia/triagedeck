package com.triagedeck.auth.verification;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 注册验证码：先证明邮箱是你的，再创建账号。
 *
 * <p>为什么不是"先建账号、再点邮件里的链接验证"：那样别人可以抢先用你的邮箱注册一个账号（密码是他设的），
 * 等你以后想注册时邮箱已被占用；如果他还拿这个账号接受了发给你邮箱的邀请，就混进了你的组织。
 * 现在没拿到验证码就建不了账号，数据库里每个账号的邮箱都验证过。
 *
 * <p>验证码存在 Redis 里（一个 hash：code 和已尝试次数），到期自动删除，不需要建表、也不需要定时清理。
 * 验证码明文存放：6 位数字一共只有一百万种，就算存哈希，拿到 Redis 数据的人也能瞬间把一百万个哈希全算一遍，
 * 哈希在这里起不到保护作用。真正挡住猜测的是"最多试 5 次"和"10 分钟过期"。
 */
@Service
public class RegistrationCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    // 先删掉旧的（连同尝试次数），再写新验证码并设过期时间。放在一个脚本里，不会出现"写了但没设过期"的 key
    private static final RedisScript<Void> ISSUE = RedisScript.of("""
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'code', ARGV[1], 'attempts', 0)
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            """);

    // 尝试次数加一，返回存着的验证码；验证码不存在（过期、用过）或次数超了，返回 nil。
    // "加一"和"读出来"在一个脚本里原子执行：并发猜也不会绕过次数限制。
    // 次数超了不删 key：删除只留给"验证码正确"那一步，否则并发时一个超次数的请求会把别人正要用的正确验证码删掉，
    // 结果谁都注册不了。超次数的 key 之后每次都会被拒绝，到期自动消失，重新获取验证码时也会被覆盖
    private static final RedisScript<String> ATTEMPT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return false
            end
            if redis.call('HINCRBY', KEYS[1], 'attempts', 1) > tonumber(ARGV[1]) then
                return false
            end
            return redis.call('HGET', KEYS[1], 'code')
            """, String.class);

    private final StringRedisTemplate redis;
    private final AppUserRepository userRepository;
    private final RegistrationEmailSender emailSender;
    private final RegistrationCodeProperties properties;

    public RegistrationCodeService(
            StringRedisTemplate redis,
            AppUserRepository userRepository,
            RegistrationEmailSender emailSender,
            RegistrationCodeProperties properties) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.emailSender = emailSender;
        this.properties = properties;
    }

    /**
     * 给邮箱发验证码。接口不管结果都返回 204，所以调用方看不出邮箱有没有注册过、是不是还在冷却。
     *
     * <p>@Async：整个方法在后台线程执行，接口立刻返回。否则"已注册"（查库后发提醒）、"冷却中"（什么都不做）、
     * "新邮箱"（写 Redis、发信）三种情况的响应时间不一样，别人可以靠计时判断。
     *
     * <p>冷却对已注册的邮箱同样生效，不然别人可以用这个接口不停给一个已注册的用户发提醒邮件。
     */
    @Async
    public void sendCode(String email) {
        String normalized = AppUser.normalizeEmail(email);
        // SET NX：只有冷却 key 不存在时才设置成功，并发的两个请求只有一个能发信
        Boolean first = redis.opsForValue()
                .setIfAbsent("registration-code-cooldown:" + normalized, "1", properties.resendCooldown());
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        if (userRepository.existsByEmail(normalized)) {
            emailSender.sendAlreadyRegistered(normalized);
            return;
        }
        emailSender.sendCode(normalized, issueCode(normalized), properties.ttl().toMinutes());
    }

    /**
     * 生成新验证码存进 Redis 并返回它，同一个邮箱之前的验证码随之作废。不发信、不检查冷却。
     * 正常流程由 sendCode 调用；测试也直接用它拿验证码，省掉收信这一步。
     */
    public String issueCode(String email) {
        // nextInt(1_000_000) 在 0 到 999999 之间均匀分布，不足 6 位前面补 0
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        redis.execute(
                ISSUE,
                List.of(key(email)),
                code,
                String.valueOf(properties.ttl().toMillis()));
        return code;
    }

    /**
     * 校验验证码，对了就立刻删掉（只能用一次）。不存在、过期、错误、试太多次，都抛 INVALID_VERIFICATION_CODE：
     * 前端的处理都一样，让用户检查输入或重新获取。
     */
    public void verifyAndConsume(String email, String code) {
        String key = key(email);
        String stored = redis.execute(ATTEMPT, List.of(key), String.valueOf(properties.maxAttempts()));
        // MessageDigest.isEqual 的耗时和"前几位对了"无关，不会通过响应快慢泄露验证码
        boolean matches = stored != null
                && MessageDigest.isEqual(
                        stored.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        // 删除成功才算数：同一个验证码被两个请求同时提交时，只有先删掉它的那个能继续注册
        if (!matches || !Boolean.TRUE.equals(redis.delete(key))) {
            throw new BusinessException(ErrorCode.INVALID_VERIFICATION_CODE);
        }
    }

    private static String key(String email) {
        return "registration-code:" + AppUser.normalizeEmail(email);
    }
}

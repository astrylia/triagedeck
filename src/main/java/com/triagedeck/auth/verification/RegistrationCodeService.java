package com.triagedeck.auth.verification;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.ratelimit.RateLimitExceededException;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
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

    // 核对前的检查，返回 {结果, 附加值}：
    //   {'invalid'}：验证码不存在（过期、用过）或这个验证码的次数超了
    //   {'locked', 剩余毫秒}：这个邮箱 24 小时内输错太多次
    //   {'code', 验证码}：可以核对，同时这个验证码的尝试次数已经加一
    // 放在一个脚本里原子执行：并发猜也不会绕过单个验证码的次数限制。
    // 次数超了不删 key：删除只留给"验证码正确"那一步，否则并发时一个超次数的请求会把别人正要用的正确验证码删掉，
    // 结果谁都注册不了。超次数的 key 之后每次都会被拒绝，到期自动消失，重新获取验证码时也会被覆盖
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ATTEMPT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return {'invalid'}
            end
            local failures = tonumber(redis.call('GET', KEYS[2]) or '0')
            if failures >= tonumber(ARGV[2]) then
                return {'locked', redis.call('PTTL', KEYS[2])}
            end
            if redis.call('HINCRBY', KEYS[1], 'attempts', 1) > tonumber(ARGV[1]) then
                return {'invalid'}
            end
            return {'code', redis.call('HGET', KEYS[1], 'code')}
            """, List.class);

    // 记一次输错。第一次时设过期时间，窗口从第一次输错开始算
    private static final RedisScript<Long> RECORD_FAILURE = RedisScript.of("""
            local failures = redis.call('INCR', KEYS[1])
            if failures == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return failures
            """, Long.class);

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
     * 校验验证码，对了就立刻删掉（只能用一次）。不存在、过期、错误、这个验证码试太多次，都抛 INVALID_VERIFICATION_CODE：
     * 前端的处理都一样，让用户检查输入或重新获取。
     *
     * <p>除了"每个验证码最多试 5 次"，同一个邮箱在一段时间内（默认 24 小时）输错的总次数也有上限（默认 10 次），
     * 超了抛 429。这个计数不随重新获取验证码清零（NIST SP 800-63B 第 4 版 3.1.3.2 节的要求）：
     * 否则攻击者每分钟要一个新验证码再猜 5 次，一天能猜 7200 次，一个月有约 20% 的机会蒙中。
     * 现在一天最多猜 10 次，蒙中一个 6 位数验证码的概率约十万分之一。
     *
     * <p>代价：别人可以故意输错 10 次，让这个邮箱 24 小时内注册不了。被影响的只是一个还没注册的邮箱，
     * 等一天就能恢复，比让人有机会冒用邮箱划算。
     */
    public void verifyAndConsume(String email, String code) {
        String codeKey = key(email);
        String failuresKey = "registration-code-failures:" + AppUser.normalizeEmail(email);
        List<?> result = redis.execute(
                ATTEMPT,
                List.of(codeKey, failuresKey),
                String.valueOf(properties.maxAttempts()),
                String.valueOf(properties.maxFailuresPerEmail()));
        String status = (String) result.get(0);
        if (status.equals("locked")) {
            long millisLeft = ((Number) result.get(1)).longValue();
            throw new RateLimitExceededException(Duration.ofMillis(Math.max(millisLeft, 0)));
        }
        String stored = status.equals("code") ? (String) result.get(1) : null;
        // MessageDigest.isEqual 的耗时和"前几位对了"无关，不会通过响应快慢泄露验证码
        boolean matches = stored != null
                && MessageDigest.isEqual(
                        stored.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            // 只有真的拿验证码比过才算输错。上面"查上限"和这里"加一"不是同一次原子操作，
            // 并发猜时总数可能略超上限，但每个验证码本身最多 5 次，超出的量有界
            if (stored != null) {
                redis.execute(
                        RECORD_FAILURE,
                        List.of(failuresKey),
                        String.valueOf(properties.failureWindow().toMillis()));
            }
            throw new BusinessException(ErrorCode.INVALID_VERIFICATION_CODE);
        }
        // 删除成功才算数：同一个验证码被两个请求同时提交时，只有先删掉它的那个能继续注册
        if (!Boolean.TRUE.equals(redis.delete(codeKey))) {
            throw new BusinessException(ErrorCode.INVALID_VERIFICATION_CODE);
        }
    }

    private static String key(String email) {
        return "registration-code:" + AppUser.normalizeEmail(email);
    }
}

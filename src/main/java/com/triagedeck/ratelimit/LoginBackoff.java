package com.triagedeck.ratelimit;

import com.triagedeck.user.AppUser;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 同一个邮箱连续输错密码时，让下一次尝试等得越来越久（NIST SP 800-63B 第 4 版 3.2.2 节的做法之一）。
 *
 * <p>和 RateLimiter 的"每分钟最多几次"不同，这里数的是<b>连续</b>失败：登录成功就清零。
 * 正常用户偶尔输错几次不受影响；猜密码的人每次都失败，等待时间翻倍，一天最多试一百来次。
 *
 * <p>按邮箱计数，不按 IP（OWASP：攻击者换 IP 很容易）。不存在的邮箱也照样计数、照样要等，
 * 否则"这个邮箱要等、那个不用等"就暴露了哪些邮箱注册过。
 *
 * <p>只延迟、不停用账号：停用之后要靠"忘记密码"恢复，这个功能还没做。
 * 代价是别人可以故意输错，让真用户最多等 maxDelay（默认 15 分钟）。
 */
@Component
public class LoginBackoff {

    private static final Logger log = LoggerFactory.getLogger(LoginBackoff.class);

    // 检查并"占位"：要等就返回还要等多少毫秒；不用等就把计数加一、记下这次的时间，返回 0。
    // 一次尝试先算作失败，登录成功再清零。这样并发打进来的一批请求，只有第一个能拿到这次机会，
    // 不会出现"计数还没来得及加，一批请求都通过了"。时间用 Redis 自己的 TIME，多台应用服务器的时钟不一致也没关系。
    // ARGV: 1 免等次数，2 起始等待毫秒，3 最长等待毫秒，4 多久没再试就清零（毫秒）
    private static final RedisScript<Long> ATTEMPT = RedisScript.of("""
            local now_parts = redis.call('TIME')
            local now = tonumber(now_parts[1]) * 1000 + math.floor(tonumber(now_parts[2]) / 1000)
            local failures = tonumber(redis.call('HGET', KEYS[1], 'failures') or '0')
            local free = tonumber(ARGV[1])
            if failures >= free then
                local wait = math.min(tonumber(ARGV[2]) * 2 ^ (failures - free), tonumber(ARGV[3]))
                local last = tonumber(redis.call('HGET', KEYS[1], 'last') or '0')
                if now - last < wait then
                    return math.floor(wait - (now - last))
                end
            end
            redis.call('HINCRBY', KEYS[1], 'failures', 1)
            redis.call('HSET', KEYS[1], 'last', now)
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public LoginBackoff(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 登录比对密码之前调用。还在等待期就抛 RateLimitExceededException（429，带 Retry-After），
     * 否则这次尝试先记为一次失败。Redis 不可用时放行，和 RateLimiter 一样。
     */
    public void beforeAttempt(String email) {
        if (!properties.enabled()) {
            return;
        }
        RateLimitProperties.Backoff rule = properties.loginBackoff();
        Long millisLeft;
        try {
            millisLeft = redis.execute(
                    ATTEMPT,
                    List.of(key(email)),
                    String.valueOf(rule.freeAttempts()),
                    String.valueOf(rule.baseDelay().toMillis()),
                    String.valueOf(rule.maxDelay().toMillis()),
                    String.valueOf(rule.resetAfter().toMillis()));
        } catch (DataAccessException e) {
            log.warn("Login backoff unavailable, allowing attempt", e);
            return;
        }
        if (millisLeft != null && millisLeft > 0) {
            throw new RateLimitExceededException(Duration.ofMillis(millisLeft));
        }
    }

    /** 登录成功：连续失败清零。 */
    public void succeeded(String email) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redis.delete(key(email));
        } catch (DataAccessException e) {
            log.warn("Login backoff unavailable, could not reset failures", e);
        }
    }

    private static String key(String email) {
        return "login-failures:" + AppUser.normalizeEmail(email);
    }
}

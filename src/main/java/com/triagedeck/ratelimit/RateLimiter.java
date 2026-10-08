package com.triagedeck.ratelimit;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的限流，算法是"固定窗口计数"：每个 key 在一个时间窗口里有一个计数器，超过上限就拒绝，窗口过期后自动清零。
 *
 * <p>为什么用 Redis 而不是放在应用内存里：部署多个实例时，内存里的计数各算各的，限流就失效了；
 * 所有实例共用同一个 Redis，计数才是全局的。
 *
 * <p>"加一"和"第一次时设置过期时间"必须是一个原子操作，所以用 Lua 脚本：Redis 执行脚本时不会插入别的命令。
 * 如果分成 INCR、EXPIRE 两条命令，中间进程挂了，这个 key 就永远不过期，用户会被永久限流。
 *
 * <p>固定窗口的已知局限：窗口交界处的短时间内最多能通过两倍的次数（上个窗口末尾 + 下个窗口开头）。
 * 对防暴力破解来说这点误差无所谓，换来的是实现简单。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    // 返回 {当前计数, 距窗口结束还剩多少毫秒}
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 记一次请求；如果这个 key 在当前窗口里已经超过上限，抛 RateLimitExceededException（429）。
     *
     * <p>Redis 连不上时放行并记日志：限流是保护措施，不能因为它挂了就让所有人都登录不了。
     */
    public void check(String key, RateLimitProperties.Rule rule) {
        if (!properties.enabled()) {
            return;
        }
        List<?> result;
        try {
            result = redis.execute(
                    INCREMENT,
                    List.of("rate-limit:" + key),
                    String.valueOf(rule.window().toMillis()));
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing request for {}", key, e);
            return;
        }
        long count = ((Number) result.get(0)).longValue();
        long millisLeft = ((Number) result.get(1)).longValue();
        if (count > rule.limit()) {
            throw new RateLimitExceededException(Duration.ofMillis(Math.max(millisLeft, 0)));
        }
    }
}

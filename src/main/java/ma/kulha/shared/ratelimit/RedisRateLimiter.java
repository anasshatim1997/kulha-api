package ma.kulha.shared.ratelimit;

import java.time.Duration;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Shared fixed-window limiter. A Lua script keeps increment and expiry atomic across API instances. */
@Component
@Profile("!test")
class RedisRateLimiter implements RateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT_AND_EXPIRE = new DefaultRedisScript<>(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """,
            Long.class);

    private final StringRedisTemplate redis;

    RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int maxRequests, Duration window) {
        if (maxRequests < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate limit and window must be positive");
        }
        long windowMillis = Math.max(1, window.toMillis());
        Long count = redis.execute(INCREMENT_AND_EXPIRE, java.util.List.of("kulha:rate-limit:" + key),
                Long.toString(windowMillis));
        long safeCount = Objects.requireNonNull(count, "Redis rate-limit script returned no counter");
        if (safeCount <= maxRequests) return RateLimitResult.permitted();
        Long ttlMillis = redis.getExpire("kulha:rate-limit:" + key);
        long retryAfter = ttlMillis == null || ttlMillis < 0 ? 1 : (ttlMillis + 999) / 1000;
        return RateLimitResult.denied(retryAfter);
    }
}

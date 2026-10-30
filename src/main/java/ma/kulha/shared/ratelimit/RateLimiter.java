package ma.kulha.shared.ratelimit;

import java.time.Duration;

/**
 * Counts requests per key. The in-memory implementation is for a single instance;
 * milestone M9 replaces it with a Redis-backed one so several instances share the counters.
 */
public interface RateLimiter {

    /** Counts one request for the key and says whether it is still within the limit. */
    RateLimitResult tryAcquire(String key, int maxRequests, Duration window);
}

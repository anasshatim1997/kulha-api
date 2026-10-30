package ma.kulha.shared.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fixed-window counters in memory. Simple and safe for one instance; not shared between instances. */
@Component
class InMemoryRateLimiter implements RateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long startMillis, long lengthMillis, int count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final boolean enabled;

    InMemoryRateLimiter(Clock clock, @Value("${app.rate-limit.enabled:true}") boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    public RateLimitResult tryAcquire(String key, int maxRequests, Duration window) {
        if (!enabled) {
            return RateLimitResult.permitted();
        }
        long now = clock.millis();
        long lengthMillis = window.toMillis();

        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || now - current.startMillis() >= lengthMillis) {
                return new Window(now, lengthMillis, 1);
            }
            return new Window(current.startMillis(), lengthMillis, current.count() + 1);
        });

        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= e.getValue().lengthMillis());
        }

        if (updated.count() > maxRequests) {
            long remainingMillis = updated.startMillis() + lengthMillis - now;
            return RateLimitResult.denied((remainingMillis + 999) / 1000);
        }
        return RateLimitResult.permitted();
    }
}

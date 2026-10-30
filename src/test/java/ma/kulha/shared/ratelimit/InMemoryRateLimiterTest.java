package ma.kulha.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    /** A clock the test can move forward. */
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-30T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock, true);

    @Test
    void allowsRequestsUpToTheLimitThenDenies() {
        assertThat(limiter.tryAcquire("k", 3, MINUTE).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 3, MINUTE).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 3, MINUTE).allowed()).isTrue();

        assertThat(limiter.tryAcquire("k", 3, MINUTE).allowed()).isFalse();
    }

    @Test
    void retryAfterIsPositiveAndNeverLongerThanTheWindow() {
        limiter.tryAcquire("k", 1, MINUTE);
        clock.advance(Duration.ofSeconds(20));

        RateLimitResult denied = limiter.tryAcquire("k", 1, MINUTE);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void theCounterStartsAgainWhenTheWindowHasPassed() {
        limiter.tryAcquire("k", 1, MINUTE);
        assertThat(limiter.tryAcquire("k", 1, MINUTE).allowed()).isFalse();

        clock.advance(Duration.ofSeconds(61));

        assertThat(limiter.tryAcquire("k", 1, MINUTE).allowed()).isTrue();
    }

    @Test
    void keysAreCountedSeparately() {
        limiter.tryAcquire("a", 1, MINUTE);
        assertThat(limiter.tryAcquire("a", 1, MINUTE).allowed()).isFalse();

        assertThat(limiter.tryAcquire("b", 1, MINUTE).allowed()).isTrue();
    }

    @Test
    void aDisabledLimiterAlwaysAllows() {
        InMemoryRateLimiter disabled = new InMemoryRateLimiter(clock, false);

        for (int i = 0; i < 100; i++) {
            assertThat(disabled.tryAcquire("k", 1, MINUTE).allowed()).isTrue();
        }
    }
}

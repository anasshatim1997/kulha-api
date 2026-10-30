package ma.kulha.shared.ratelimit;

public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

    public static RateLimitResult permitted() {
        return new RateLimitResult(true, 0);
    }

    public static RateLimitResult denied(long retryAfterSeconds) {
        return new RateLimitResult(false, Math.max(1, retryAfterSeconds));
    }
}

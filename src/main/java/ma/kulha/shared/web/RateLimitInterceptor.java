package ma.kulha.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import ma.kulha.shared.ratelimit.RateLimitResult;
import ma.kulha.shared.ratelimit.RateLimiter;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-IP limits on the public authentication endpoints. Account-level limits live in the services.
 * Behind a reverse proxy, the real client address must be configured (see PLAN.md, step D2),
 * otherwise every user shares the proxy's address.
 */
@Component
class RateLimitInterceptor implements HandlerInterceptor {

    private record Rule(String name, int maxRequests, Duration window) {
    }

    private static final Map<String, Rule> RULES = Map.of(
            "/api/v1/auth/login", new Rule("login", 10, Duration.ofMinutes(1)),
            "/api/v1/auth/refresh", new Rule("refresh", 30, Duration.ofMinutes(1)),
            "/api/v1/auth/register", new Rule("register", 10, Duration.ofHours(1)),
            "/api/v1/auth/verify-email", new Rule("verify-email", 20, Duration.ofHours(1)),
            "/api/v1/auth/resend-verification", new Rule("resend-verification", 5, Duration.ofHours(1)),
            "/api/v1/auth/forgot-password", new Rule("forgot-password", 5, Duration.ofHours(1)),
            "/api/v1/auth/reset-password", new Rule("reset-password", 10, Duration.ofHours(1)));

    private final RateLimiter limiter;

    RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        Rule rule = RULES.get(request.getRequestURI());
        if (rule == null) {
            return true;
        }
        RateLimitResult result = limiter.tryAcquire(
                "ip:" + rule.name() + ":" + request.getRemoteAddr(), rule.maxRequests(), rule.window());
        if (result.allowed()) {
            return true;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Too many requests\",\"status\":429,"
                        + "\"detail\":\"Too many requests. Try again later.\"}");
        return false;
    }
}

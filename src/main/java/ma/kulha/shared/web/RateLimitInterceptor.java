package ma.kulha.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import ma.kulha.shared.ratelimit.RateLimitResult;
import ma.kulha.shared.ratelimit.RateLimiter;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-IP limits on the public authentication endpoints. Account-level limits live in the services.
 * The BFF client-IP header is trusted only when accompanied by the configured shared secret;
 * otherwise the servlet's remote address is used. See the deployment README for proxy requirements.
 */
@Component
class RateLimitInterceptor implements HandlerInterceptor {

    private record Rule(String name, int maxRequests, Duration window) {
    }

    private static final Map<String, Rule> RULES = Map.of(
            "/api/v1/auth/login", new Rule("login", 10, Duration.ofMinutes(1)),
            "/api/v1/auth/google", new Rule("google-login", 10, Duration.ofMinutes(1)),
            "/api/v1/auth/refresh", new Rule("refresh", 30, Duration.ofMinutes(1)),
            "/api/v1/auth/register", new Rule("register", 10, Duration.ofHours(1)),
            "/api/v1/auth/email-status", new Rule("email-status", 12, Duration.ofMinutes(1)),
            "/api/v1/auth/verify-email", new Rule("verify-email", 20, Duration.ofHours(1)),
            "/api/v1/auth/resend-verification", new Rule("resend-verification", 5, Duration.ofHours(1)),
            "/api/v1/auth/forgot-password", new Rule("forgot-password", 5, Duration.ofHours(1)),
            "/api/v1/auth/reset-password", new Rule("reset-password", 10, Duration.ofHours(1)));

    private static final String CLIENT_IP_HEADER = "X-Kulha-Client-IP";
    private static final String BFF_SECRET_HEADER = "X-Kulha-Bff-Secret";

    private final RateLimiter limiter;
    private final byte[] expectedBffSecret;

    RateLimitInterceptor(RateLimiter limiter,
                         @Value("${app.rate-limit.bff-secret:}") String bffSecret) {
        this.limiter = limiter;
        this.expectedBffSecret = bffSecret.getBytes(StandardCharsets.UTF_8);
    }

    private String trustedClientIp(HttpServletRequest request) {
        String suppliedIp = request.getHeader(CLIENT_IP_HEADER);
        String suppliedSecret = request.getHeader(BFF_SECRET_HEADER);
        if (expectedBffSecret.length > 0 && suppliedSecret != null
                && MessageDigest.isEqual(expectedBffSecret, suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            String validated = validateIp(suppliedIp);
            if (validated != null) {
                return validated;
            }
        }
        return request.getRemoteAddr();
    }

    private static String validateIp(String value) {
        if (value == null || value.isBlank() || value.length() > 45
                || !value.equals(value.trim()) || value.indexOf('%') >= 0) {
            return null;
        }
        if (value.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(value);
                return address instanceof Inet6Address ? address.getHostAddress() : null;
            } catch (UnknownHostException invalid) {
                return null;
            }
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || !octet.chars().allMatch(c -> c >= '0' && c <= '9')
                    || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return null;
            }
            try {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        return String.join(".", octets);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler)
            throws IOException {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        Rule rule = RULES.get(request.getRequestURI());
        if (rule == null) {
            return true;
        }
        String clientIp = trustedClientIp(request);
        RateLimitResult result = limiter.tryAcquire(
                "ip:" + rule.name() + ":" + clientIp, rule.maxRequests(), rule.window());
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

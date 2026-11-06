package ma.kulha.shared.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
class RequestBodySizeLimitFilter extends OncePerRequestFilter {
    private static final HttpStatusCode PAYLOAD_TOO_LARGE = HttpStatusCode.valueOf(413);
    private final ObjectMapper objectMapper;
    private final int maxBodyBytes;

    RequestBodySizeLimitFilter(ObjectMapper objectMapper,
                               @Value("${app.http.max-body-bytes:1048576}") int maxBodyBytes) {
        if (maxBodyBytes < 1 || maxBodyBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("app.http.max-body-bytes must be between 1 and 2147483646");
        }
        this.objectMapper = objectMapper;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (!isJson(request.getContentType())) {
            chain.doFilter(request, response);
            return;
        }

        if (request.getContentLengthLong() > maxBodyBytes) {
            reject(response);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (body.length > maxBodyBytes) {
            reject(response);
            return;
        }

        chain.doFilter(new CachedJsonRequest(request, body), response);
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("application/json")
                || normalized.startsWith("application/") && normalized.contains("+json");
    }

    private void reject(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(PAYLOAD_TOO_LARGE,
                "The JSON request body exceeds the configured size limit.");
        problem.setTitle("Payload too large");
        response.setStatus(PAYLOAD_TOO_LARGE.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static final class CachedJsonRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedJsonRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) {
                    throw new IllegalStateException("Asynchronous request-body reads are not supported.");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }
}

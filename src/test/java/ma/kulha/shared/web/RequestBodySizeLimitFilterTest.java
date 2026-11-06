package ma.kulha.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RequestBodySizeLimitFilterTest {

    @Test
    void rejectsJsonBodyLargerThanConfiguredLimit() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(new ObjectMapper(), 8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setContentType("application/json");
        request.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("Payload too large");
        assertThat(chainCalled.get()).isFalse();
    }

    @Test
    void rejectsOversizedJsonBodyWhenContentLengthIsUnknown() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(new ObjectMapper(), 8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContentType("application/json");
        request.setContent("123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chainCalled.get()).isFalse();
    }

    @Test
    void passesJsonBodyAtConfiguredLimitUnchanged() throws Exception {
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(new ObjectMapper(), 8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setContentType("application/json");
        request.setContent("12345678".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> received = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> received.set(
                new String(((jakarta.servlet.http.HttpServletRequest) req).getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(received.get()).isEqualTo("12345678");
    }
}

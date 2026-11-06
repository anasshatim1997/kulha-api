package ma.kulha.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesAValidCorrelationIdDuringTheRequestAndReturnsItInTheResponse() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.addHeader("X-Correlation-Id", "test-correlation-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get("correlationId")).isEqualTo("test-correlation-123"));

        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("test-correlation-123");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void replacesAnInvalidCorrelationIdWithASafeGeneratedValue() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader("X-Correlation-Id", "bad value\r\nInjected: yes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            String correlationId = MDC.get("correlationId");
            assertThat(correlationId).matches("[0-9a-f-]{36}");
        });

        assertThat(response.getHeader("X-Correlation-Id")).matches("[0-9a-f-]{36}");
        assertThat(MDC.get("correlationId")).isNull();
    }
}

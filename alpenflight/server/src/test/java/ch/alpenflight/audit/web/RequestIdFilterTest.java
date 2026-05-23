package ch.alpenflight.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Drives {@link RequestIdFilter}'s small contract:
 * <ul>
 *   <li>a fresh request gets a generated UUID v7 stamped into the
 *       {@code requestId} MDC key and echoed on {@code X-Request-Id};</li>
 *   <li>an inbound {@code X-Request-Id} passes through verbatim when it
 *       satisfies the sanitiser;</li>
 *   <li>an inbound header with control characters is rejected — a fresh
 *       UUID is generated instead (defends against header injection);</li>
 *   <li>the MDC key is cleared on completion so the next request starts
 *       clean.</li>
 * </ul>
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generates_uuid_v7_when_no_inbound_header() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] capturedMdc = new String[1];
        FilterChain chain = (request, response) -> capturedMdc[0] = MDC.get(RequestIdFilter.MDC_KEY);

        filter.doFilter(req, res, chain);

        assertThat(capturedMdc[0]).isNotNull();
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo(capturedMdc[0]);
        // UUID v7 string form: 36-char canonical UUID.
        assertThat(capturedMdc[0]).hasSize(36).matches("^[0-9a-f-]+$");
    }

    @Test
    void honours_inbound_header_when_safe() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/anything");
        req.addHeader(RequestIdFilter.HEADER, "corr-12345");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] capturedMdc = new String[1];

        filter.doFilter(req, res, (request, response) ->
                capturedMdc[0] = MDC.get(RequestIdFilter.MDC_KEY));

        assertThat(capturedMdc[0]).isEqualTo("corr-12345");
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo("corr-12345");
    }

    @Test
    void rejects_inbound_header_with_control_chars() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/anything");
        req.addHeader(RequestIdFilter.HEADER, "corr\r\nInjected-Header: bad");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] capturedMdc = new String[1];

        filter.doFilter(req, res, (request, response) ->
                capturedMdc[0] = MDC.get(RequestIdFilter.MDC_KEY));

        assertThat(capturedMdc[0]).isNotEqualTo("corr\r\nInjected-Header: bad");
        assertThat(capturedMdc[0]).hasSize(36);
    }

    @Test
    void rejects_inbound_header_when_oversize() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/anything");
        req.addHeader(RequestIdFilter.HEADER, "x".repeat(65));
        MockHttpServletResponse res = new MockHttpServletResponse();
        String[] capturedMdc = new String[1];

        filter.doFilter(req, res, (request, response) ->
                capturedMdc[0] = MDC.get(RequestIdFilter.MDC_KEY));

        assertThat(capturedMdc[0]).hasSize(36);
    }

    @Test
    void clears_mdc_after_completion() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (request, response) -> {
            // chain populates the key — verified by the other tests
        });

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}

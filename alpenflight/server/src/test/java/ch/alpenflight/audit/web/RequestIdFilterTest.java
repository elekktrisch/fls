package ch.alpenflight.audit.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
        });

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}

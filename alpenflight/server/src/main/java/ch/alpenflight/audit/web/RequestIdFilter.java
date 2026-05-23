package ch.alpenflight.audit.web;

import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Minimal request-id propagation — generate a UUID v7 per request, populate
 * the {@code requestId} MDC key, echo it on the response as
 * {@code X-Request-Id}. Honors an inbound {@code X-Request-Id} header when
 * present so end-to-end correlation across a proxy chain works on day one.
 *
 * <p>S-031 owns the long-term correlation-ID story (structured JSON logging
 * + sampling); until that ships, S-027's audit listener reads this MDC key
 * to stamp {@code mutation_audit_event.request_id}. The MDC key name
 * ({@code requestId}) matches the {@code request_id} placeholder already
 * reserved in {@code logback-spring.xml}.
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} so the MDC key is set before
 * Spring Security and any downstream filter logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";
    private static final int MAX_INBOUND_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = sanitiseInbound(request.getHeader(HEADER));
        if (id == null) {
            id = UuidCreator.getTimeOrderedEpoch().toString();
        }
        MDC.put(MDC_KEY, id);
        try {
            response.setHeader(HEADER, id);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Accept an inbound header only if it's printable + bounded. Defends
     * against header-injection (CR/LF), oversize correlation strings, and
     * malicious operators tagging downstream rows with arbitrary content.
     */
    private static @Nullable String sanitiseInbound(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_INBOUND_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return null;
            }
        }
        return trimmed;
    }
}

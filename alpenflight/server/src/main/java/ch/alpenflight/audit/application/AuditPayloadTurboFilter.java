package ch.alpenflight.audit.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.stereotype.Component;

/**
 * Logback {@code TurboFilter} that drops any log statement whose formatted
 * message carries audit-payload jsonb. Two narrow signals trigger the
 * deny: the redactor's {@code [redacted]} sentinel (a serialised snapshot
 * is the only place that string legitimately appears) and the explicit
 * {@link #AUDIT_PAYLOAD_MARKER} marker — code that needs to log the
 * payload deliberately must opt out by NOT including the marker.
 *
 * <p>Application logs reference {@code mutation_audit_event.id} only; the
 * operator clicks through to the row in S-056. This filter is the
 * structural guard against drift from that convention.
 *
 * <p>Earlier revisions also denied any line containing the literal
 * strings {@code "before_state"} / {@code "after_state"} — too broad
 * (a normal "fetched mutation_audit_event" entry mentioning the column
 * name was suppressed). The narrowed rule keeps the leak-prevention
 * scope intact while leaving operational logs untouched.
 */
@Component
public class AuditPayloadTurboFilter extends TurboFilter {

    /**
     * Marker substring code uses to mark a log payload as carrying audit
     * jsonb (and therefore PII-suspect). Include it in any log format
     * that intentionally serialises an audit snapshot — the filter then
     * suppresses the line.
     */
    public static final String AUDIT_PAYLOAD_MARKER = "[audit-payload]";

    private static final String[] FORBIDDEN_TOKENS = {
            PiiRedactor.REDACTED_SENTINEL,
            AUDIT_PAYLOAD_MARKER,
    };

    @PostConstruct
    void registerWithLogbackContext() {
        org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            context.addTurboFilter(this);
            super.start();
        }
    }

    @Override
    public FilterReply decide(@Nullable Marker marker,
                              @Nullable Logger logger,
                              @Nullable Level level,
                              @Nullable String format,
                              Object @Nullable [] params,
                              @Nullable Throwable t) {
        if (containsForbiddenToken(format)) {
            return FilterReply.DENY;
        }
        if (params != null) {
            for (Object p : params) {
                if (p != null && containsForbiddenToken(p.toString())) {
                    return FilterReply.DENY;
                }
            }
        }
        return FilterReply.NEUTRAL;
    }

    private static boolean containsForbiddenToken(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (s.contains(token)) {
                return true;
            }
        }
        return false;
    }
}

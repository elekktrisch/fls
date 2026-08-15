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

@Component
public class AuditPayloadTurboFilter extends TurboFilter {

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

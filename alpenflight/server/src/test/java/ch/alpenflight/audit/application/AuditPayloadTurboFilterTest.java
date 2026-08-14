package ch.alpenflight.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AuditPayloadTurboFilterTest {

    private static final Logger LOG = LoggerFactory.getLogger(AuditPayloadTurboFilterTest.class);

    private ListAppender<ILoggingEvent> appender;
    private AuditPayloadTurboFilter filter;
    private LoggerContext context;

    @BeforeEach
    void setUp() {
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);

        filter = new AuditPayloadTurboFilter();
        filter.registerWithLogbackContext();
    }

    @Test
    void allows_normal_log_statements() {
        LOG.info("plain message");

        assertThat(appender.list)
                .extracting(ILoggingEvent::getMessage)
                .contains("plain message");
    }

    @Test
    void denies_message_containing_redacted_sentinel() {
        LOG.info("audit fired: " + PiiRedactor.REDACTED_SENTINEL + " inside");

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains(PiiRedactor.REDACTED_SENTINEL));
    }

    @Test
    void denies_message_carrying_explicit_audit_payload_marker() {
        LOG.info("debug dump {} {}",
                AuditPayloadTurboFilter.AUDIT_PAYLOAD_MARKER,
                "{\"name\":\"x\"}");

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains(AuditPayloadTurboFilter.AUDIT_PAYLOAD_MARKER));
    }

    @Test
    void allows_log_referencing_before_state_column_name_only() {
        LOG.info("repository hit before_state column count={}", 12);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("before_state"));
    }

    @Test
    void denies_when_argument_carries_marker() {
        LOG.warn("payload was {}", "[redacted]");

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains(PiiRedactor.REDACTED_SENTINEL));
    }

    @Test
    void allows_log_referencing_audit_row_id_only() {
        LOG.info("recorded audit row id={}", "019e30c3-2c00-7777-8000-000000000777");

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("019e30c3-2c00-7777-8000-000000000777"));
    }

    @org.junit.jupiter.api.AfterEach
    void detach() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        context.getTurboFilterList().remove(filter);
    }
}

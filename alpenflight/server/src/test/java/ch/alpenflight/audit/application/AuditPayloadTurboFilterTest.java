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

/**
 * Drives the {@link AuditPayloadTurboFilter} contract: any log statement
 * whose formatted message contains an audit-payload marker is dropped
 * before it reaches an appender. Both formatted-string and argument
 * payloads are covered.
 */
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
    void denies_message_referencing_before_state_field() {
        LOG.info("debug dump before_state: {}", "{\"name\":\"x\"}");

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains("before_state"));
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

    /** Belt-and-braces clean-up so subsequent test classes start with a clean filter chain. */
    @org.junit.jupiter.api.AfterEach
    void detach() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        context.getTurboFilterList().remove(filter);
    }
}

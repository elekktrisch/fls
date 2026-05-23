package ch.alpenflight.audit.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Glue config — binds {@link AuditRedactionProperties} from
 * {@code audit.redaction.*} in {@code application.yml}. Kept narrow so the
 * audit module's bean wiring stays auto-component-scan-driven for
 * everything else.
 */
@Configuration
@EnableConfigurationProperties(AuditRedactionProperties.class)
class AuditConfig {
}

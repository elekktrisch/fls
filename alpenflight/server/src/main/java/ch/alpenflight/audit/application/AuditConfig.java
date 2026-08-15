package ch.alpenflight.audit.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuditRedactionProperties.class)
class AuditConfig {
}

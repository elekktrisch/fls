package ch.alpenflight.platform.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link MailSettings} from {@code alpenflight.mail.*}. Spring Boot's
 * {@code MailSenderAutoConfiguration} (triggered by {@code spring.mail.host})
 * supplies the {@code JavaMailSender}, and the thymeleaf auto-config supplies
 * the {@code TemplateEngine} — so no transport / engine bean is declared here.
 */
@Configuration
@EnableConfigurationProperties(MailSettings.class)
class MailConfig {
}

package ch.alpenflight.platform.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alpenflight.mail")
public record MailSettings(boolean enabled, String from) {

    public MailSettings {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("alpenflight.mail.from must be set");
        }
    }
}

package ch.alpenflight.platform.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-side mail toggle + sender identity, bound from
 * {@code alpenflight.mail.*}. Distinct from Spring's own {@code spring.mail.*}
 * (host/port/auth) — this is the kill-switch the send-path reads plus the
 * static from-address.
 *
 * <p>{@code enabled} defaults to {@code false} in the base profile so a
 * misconfigured environment never sends; dev + test flip it true (mailpit).
 * The per-tenant/per-locale sender identity is a deferred ADR-0013 follow-up.
 *
 * @param enabled whether the send-path actually dispatches to SMTP
 * @param from the static {@code From:} address
 */
@ConfigurationProperties(prefix = "alpenflight.mail")
public record MailSettings(boolean enabled, String from) {

    public MailSettings {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("alpenflight.mail.from must be set");
        }
    }
}

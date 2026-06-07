package ch.alpenflight.platform.mail;

/**
 * Port for dispatching a fully-rendered {@link MailMessage}. The production
 * adapter is {@link SmtpMailSender} (Spring {@code JavaMailSender} over SMTP);
 * integration tests register a {@code @Primary} captured-outbox fake so a sent
 * message is asserted deterministically without a live SMTP server (ADR 0013:
 * "emails can be asserted by content without a real SMTP server").
 *
 * <p>Templating is NOT this port's concern — callers that want a templated
 * message go through {@link TemplatedMailService}, which renders then delegates
 * here.
 */
public interface MailSender {

    /**
     * Send a rendered message. Implementations decide the transport; a
     * disabled send-path may no-op (see {@link SmtpMailSender} when
     * {@code alpenflight.mail.enabled=false}).
     */
    void send(MailMessage message);
}

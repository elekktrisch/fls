package ch.alpenflight.platform.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Production {@link MailSender} adapter — builds a MIME message via Spring's
 * {@link JavaMailSender} and dispatches it over SMTP (mailpit in dev/test;
 * a real relay in prod once the relay-choice follow-up ships, ADR 0013).
 *
 * <p>Honors the {@code alpenflight.mail.enabled} kill-switch: when disabled the
 * send is a logged no-op, so an environment that hasn't opted in (default prod)
 * never reaches the network even if {@code spring.mail.*} points somewhere.
 */
@Component
class SmtpMailSender implements MailSender {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SmtpMailSender.class);

    private final JavaMailSender javaMailSender;
    private final MailSettings settings;

    SmtpMailSender(JavaMailSender javaMailSender, MailSettings settings) {
        this.javaMailSender = javaMailSender;
        this.settings = settings;
    }

    @Override
    public void send(MailMessage message) {
        if (!settings.enabled()) {
            log.info(
                    "mail disabled (alpenflight.mail.enabled=false); dropping message subject='{}' to {} recipient(s)",
                    message.subject(),
                    message.to().size());
            return;
        }
        try {
            MimeMessage mime = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            helper.setFrom(settings.from());
            helper.setTo(message.to().toArray(new String[0]));
            helper.setSubject(message.subject());
            helper.setText(message.htmlBody(), true);
            javaMailSender.send(mime);
            log.debug(
                    "sent mail subject='{}' to {} recipient(s)",
                    message.subject(),
                    message.to().size());
        } catch (MessagingException | MailException e) {
            throw new MailDispatchException(
                    "failed to dispatch mail subject='" + message.subject() + "'", e);
        }
    }
}

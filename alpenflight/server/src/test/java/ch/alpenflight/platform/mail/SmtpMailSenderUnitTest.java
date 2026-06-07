package ch.alpenflight.platform.mail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Unit-covers the {@link SmtpMailSender} kill-switch (ADR 0013 / J-6 T-10a):
 * with {@code alpenflight.mail.enabled=false} the send is a no-op (no SMTP
 * dispatch); when enabled it builds a MIME message and hands it to the
 * {@link JavaMailSender}. The render→outbox seam is the {@code TemplatedMailServiceIT}.
 */
class SmtpMailSenderUnitTest {

    @Test
    void enabled_builds_and_dispatches_mime_message() {
        JavaMailSender java = mock(JavaMailSender.class);
        MimeMessage mime = mock(MimeMessage.class);
        when(java.createMimeMessage()).thenReturn(mime);

        SmtpMailSender sender =
                new SmtpMailSender(java, new MailSettings(true, "noreply@alpenflight.ch"));
        sender.send(MailMessage.to("ops@example.com", "Hi", "<p>body</p>"));

        verify(java).send(mime);
    }

    @Test
    void disabled_is_a_no_op() {
        JavaMailSender java = mock(JavaMailSender.class);

        SmtpMailSender sender =
                new SmtpMailSender(java, new MailSettings(false, "noreply@alpenflight.ch"));
        sender.send(MailMessage.to("ops@example.com", "Hi", "<p>body</p>"));

        verify(java, never()).createMimeMessage();
        verify(java, never()).send(any(MimeMessage.class));
    }
}

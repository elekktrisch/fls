package ch.alpenflight.platform.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Captured-outbox fake {@link MailSender} for integration tests (J-6 T-10a,
 * ADR 0013: "emails can be asserted by content without a real SMTP server").
 * Registered as {@code @Primary} so it overrides the production
 * {@link SmtpMailSender}; every {@link #send} appends to an in-memory outbox the
 * test asserts against — no live SMTP, no testcontainer, no mailpit needed for
 * the send-path ITs.
 *
 * <p>Usage: {@code @Import(CapturedMailSender.Config.class)} on the IT, then
 * {@code @Autowired CapturedMailSender outbox}.
 */
public class CapturedMailSender implements MailSender {

    private final List<MailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(MailMessage message) {
        sent.add(message);
    }

    /** The messages captured so far, in send order. */
    public List<MailMessage> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }

    /** Wires the fake as the {@code @Primary} {@link MailSender}. */
    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        CapturedMailSender capturedMailSender() {
            return new CapturedMailSender();
        }
    }
}

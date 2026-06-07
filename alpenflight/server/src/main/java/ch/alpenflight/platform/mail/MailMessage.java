package ch.alpenflight.platform.mail;

import java.util.List;

/**
 * A fully-rendered outbound email, ready to hand to the {@link MailSender}
 * port. Immutable; the body is already-rendered HTML (the Thymeleaf step
 * happens in {@link TemplatedMailService}, before this crosses the port).
 *
 * @param to recipient addresses; at least one
 * @param subject the message subject
 * @param htmlBody the rendered HTML body
 */
public record MailMessage(List<String> to, String subject, String htmlBody) {

    public MailMessage {
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("mail message needs at least one recipient");
        }
        if (to.stream().anyMatch(addr -> addr == null || addr.isBlank())) {
            throw new IllegalArgumentException("mail recipient address must not be blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("mail subject must not be blank");
        }
        if (htmlBody == null) {
            throw new IllegalArgumentException("mail body must not be null");
        }
        to = List.copyOf(to);
    }

    /** Convenience factory for the common single-recipient case. */
    public static MailMessage to(String recipient, String subject, String htmlBody) {
        return new MailMessage(List.of(recipient), subject, htmlBody);
    }
}

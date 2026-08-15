package ch.alpenflight.platform.mail;

import java.util.List;

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

    public static MailMessage to(String recipient, String subject, String htmlBody) {
        return new MailMessage(List.of(recipient), subject, htmlBody);
    }
}

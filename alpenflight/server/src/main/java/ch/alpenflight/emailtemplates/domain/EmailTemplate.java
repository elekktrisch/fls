package ch.alpenflight.emailtemplates.domain;

import ch.alpenflight.platform.id.EmailTemplateId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_email_template")
public class EmailTemplate {

    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_LOCALE_LENGTH = 35;
    private static final int MAX_SUBJECT_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "club_id", nullable = false, updatable = false)
    private @Nullable UUID clubId;

    @Column(name = "template_key", nullable = false, length = MAX_KEY_LENGTH, updatable = false)
    private String templateKey = "";

    @Column(name = "language_locale", nullable = false, length = MAX_LOCALE_LENGTH, updatable = false)
    private String languageLocale = "";

    @Column(nullable = false, length = MAX_SUBJECT_LENGTH)
    private String subject = "";

    @Column(nullable = false)
    private String body = "";

    protected EmailTemplate() {
    }

    public static EmailTemplate customize(String templateKey,
                                          String languageLocale,
                                          String subject,
                                          String body) {
        EmailTemplate t = new EmailTemplate();
        t.templateKey = canonicalize(templateKey, "templateKey", MAX_KEY_LENGTH);
        t.languageLocale = canonicalize(languageLocale, "languageLocale", MAX_LOCALE_LENGTH);
        t.assignSubject(subject);
        t.assignBody(body);
        return t;
    }

    public void revise(String newSubject, String newBody) {
        assignSubject(newSubject);
        assignBody(newBody);
    }

    private void assignSubject(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException(
                    "subject exceeds " + MAX_SUBJECT_LENGTH + " characters");
        }
        this.subject = trimmed;
    }

    private void assignBody(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
        this.body = value.strip();
    }

    private static String canonicalize(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String canonical = value.strip().toLowerCase(Locale.ROOT);
        if (canonical.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " exceeds " + maxLength + " characters");
        }
        return canonical;
    }

    public @Nullable EmailTemplateId getId() {
        return EmailTemplateId.ofNullable(id);
    }

    public @Nullable UUID getClubId() {
        return clubId;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getLanguageLocale() {
        return languageLocale;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }
}

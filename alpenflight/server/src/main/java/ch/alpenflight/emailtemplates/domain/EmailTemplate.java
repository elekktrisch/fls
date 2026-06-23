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

/**
 * EmailTemplate aggregate root — a per-club override of a transactional-email
 * default. The AlpenFlight DB holds ONLY overrides; the system defaults are
 * S-082 Thymeleaf files, never rows. So the send-time resolver reads
 * file-default ∪ db-override, and reset is simply deleting the override row.
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} on {@code clubId}; every
 * read + write is filtered to the caller's tenant before the service sees the
 * row. Identity is {@code (clubId, templateKey, languageLocale)} — the
 * structural UNIQUE in V47.
 *
 * <p>Per ADR 0022 directive 2, business rules live here, not the schema:
 *
 * <ul>
 *   <li>{@code templateKey} is the transactional-email selector (e.g.
 *       {@code lostpassword} / {@code planningday-ok}); canonicalized
 *       lower-case so the override resolves case-insensitively regardless of
 *       how a caller cased the key.</li>
 *   <li>{@code languageLocale} is the locale string (e.g. {@code de}); a plain
 *       string (Language is not an FK aggregate), also lower-cased.</li>
 *   <li>{@code subject} and {@code body} (Thymeleaf source) are required,
 *       trimmed, non-blank — an override that renders nothing is a defect, not
 *       a valid customization.</li>
 * </ul>
 */
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
        // JPA.
    }

    /**
     * Builds a club override for ({@code templateKey}, {@code languageLocale})
     * with the given subject + body. The tenant ({@link #clubId}) is set by
     * Hibernate's {@code @TenantId} resolver on persist — do NOT pass it here.
     * The clone-on-customize upsert chooses between this factory (no existing
     * row) and {@link #revise(String, String)} (row exists).
     */
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

    /** Update-in-place of an existing override's content; identity is immutable. */
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

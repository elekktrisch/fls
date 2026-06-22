package ch.alpenflight.emailtemplates.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link EmailTemplate} persistence. Implemented by the
 * email-templates {@code infra} Spring Data JPA adapter (J-11 T-04).
 *
 * <p>EmailTemplate is tenant-scoped via Hibernate's {@code @TenantId}
 * discriminator on {@code EmailTemplate.clubId}. The discriminator rides on
 * every read + write query automatically; the service trusts it and adds only
 * the role-within-tenant checks at the controller.
 *
 * <p>Reset-to-default is {@link #delete(EmailTemplate)} of the override row —
 * no domain method, the resolver then falls back to the S-082 file default.
 */
public interface EmailTemplateRepository {

    List<EmailTemplate> findAll();

    /** The override identity used by the clone-on-customize upsert. */
    Optional<EmailTemplate> findByTemplateKeyAndLanguageLocale(String templateKey, String languageLocale);

    EmailTemplate save(EmailTemplate template);

    void delete(EmailTemplate template);

    /** Flushes the persistence context — surfaces DB-side UNIQUE races synchronously. */
    void flush();
}

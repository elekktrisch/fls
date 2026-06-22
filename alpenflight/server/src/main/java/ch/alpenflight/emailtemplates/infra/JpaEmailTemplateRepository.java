package ch.alpenflight.emailtemplates.infra;

import ch.alpenflight.emailtemplates.domain.EmailTemplate;
import ch.alpenflight.emailtemplates.domain.EmailTemplateRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA implementation of {@link EmailTemplateRepository}. Extends
 * both the abstract port and {@code JpaRepository<EmailTemplate, UUID>} so the
 * application layer depends on the port (ADR 0023) while Spring Data generates
 * the runtime bean.
 *
 * <p>EmailTemplate is tenant-scoped via {@code @TenantId} on
 * {@code EmailTemplate.clubId}; Hibernate appends the tenant predicate to every
 * query automatically, so {@code findAll()} returns only the caller's overrides
 * and the key+locale lookup never crosses a tenant boundary.
 */
public interface JpaEmailTemplateRepository
        extends JpaRepository<EmailTemplate, UUID>, EmailTemplateRepository {

    @Override
    @Query("select t from EmailTemplate t "
            + "where t.templateKey = :templateKey and t.languageLocale = :languageLocale")
    Optional<EmailTemplate> findByTemplateKeyAndLanguageLocale(
            @Param("templateKey") String templateKey,
            @Param("languageLocale") String languageLocale);
}

package ch.alpenflight.emailtemplates.infra;

import ch.alpenflight.emailtemplates.domain.EmailTemplate;
import ch.alpenflight.emailtemplates.domain.EmailTemplateRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaEmailTemplateRepository
        extends JpaRepository<EmailTemplate, UUID>, EmailTemplateRepository {

    @Override
    @Query("select t from EmailTemplate t "
            + "where t.templateKey = :templateKey and t.languageLocale = :languageLocale")
    Optional<EmailTemplate> findByTemplateKeyAndLanguageLocale(
            @Param("templateKey") String templateKey,
            @Param("languageLocale") String languageLocale);
}

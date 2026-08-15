package ch.alpenflight.emailtemplates.domain;

import java.util.List;
import java.util.Optional;

public interface EmailTemplateRepository {

    List<EmailTemplate> findAll();

    Optional<EmailTemplate> findByTemplateKeyAndLanguageLocale(String templateKey, String languageLocale);

    EmailTemplate save(EmailTemplate template);

    void delete(EmailTemplate template);

    void flush();
}

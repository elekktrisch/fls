package ch.alpenflight.emailtemplates.application;

import ch.alpenflight.emailtemplates.application.EmailTemplateCatalog.FileDefault;
import ch.alpenflight.emailtemplates.application.EmailTemplateDtos.EmailTemplateListItem;
import ch.alpenflight.emailtemplates.application.EmailTemplateDtos.TemplateSource;
import ch.alpenflight.emailtemplates.domain.EmailTemplate;

/**
 * Aggregate / file-default → DTO mapping. Pure functions; no Spring, no JPA.
 */
final class EmailTemplateMapper {

    private EmailTemplateMapper() {}

    static EmailTemplateListItem fromFileDefault(FileDefault d) {
        return new EmailTemplateListItem(
                d.templateKey(),
                d.languageLocale(),
                TemplateSource.FILE_DEFAULT,
                null,
                d.body());
    }

    static EmailTemplateListItem fromOverride(EmailTemplate t) {
        return new EmailTemplateListItem(
                t.getTemplateKey(),
                t.getLanguageLocale(),
                TemplateSource.CLUB_OVERRIDE,
                t.getSubject(),
                t.getBody());
    }
}

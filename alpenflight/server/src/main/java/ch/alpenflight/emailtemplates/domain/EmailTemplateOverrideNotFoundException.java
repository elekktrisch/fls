package ch.alpenflight.emailtemplates.domain;

public class EmailTemplateOverrideNotFoundException extends RuntimeException {

    public EmailTemplateOverrideNotFoundException(String templateKey, String languageLocale) {
        super("No club override for template '" + templateKey + "' (" + languageLocale + ")");
    }
}

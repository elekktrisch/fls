package ch.alpenflight.emailtemplates.domain;

/**
 * Thrown when reset-to-default is asked to delete a club override that does
 * not exist for {@code (templateKey, languageLocale)} in the caller's tenant.
 * The {@code @TenantId} filter scrubs other tenants' rows from the lookup, so
 * this is "no own-club override here" — 404, never 403.
 */
public class EmailTemplateOverrideNotFoundException extends RuntimeException {

    public EmailTemplateOverrideNotFoundException(String templateKey, String languageLocale) {
        super("No club override for template '" + templateKey + "' (" + languageLocale + ")");
    }
}

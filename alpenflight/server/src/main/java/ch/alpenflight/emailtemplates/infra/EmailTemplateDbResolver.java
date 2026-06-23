package ch.alpenflight.emailtemplates.infra;

import ch.alpenflight.emailtemplates.application.EmailTemplateCatalog;
import ch.alpenflight.emailtemplates.domain.EmailTemplate;
import ch.alpenflight.emailtemplates.domain.EmailTemplateRepository;
import ch.alpenflight.platform.mail.TemplatedMailService;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.cache.NonCacheableCacheEntryValidity;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.TemplateResolution;
import org.thymeleaf.templateresource.StringTemplateResource;

/**
 * Send-time Thymeleaf resolver that prefers a club's DB override over the S-082
 * file default. Spring Boot's thymeleaf auto-config collects every
 * {@link ITemplateResolver} bean into the engine and consults them by ascending
 * {@code order}; this resolver sits ahead of the default file/classpath resolver
 * so an override wins, and returns {@code null} when no override exists so the
 * chain falls through to the file default — no redeploy needed either way.
 *
 * <p>The override is the caller-club's row, scoped by Hibernate's
 * {@code @TenantId} on {@link EmailTemplate}, so the lookup never crosses a
 * tenant boundary. The logical template name the senders pass
 * ({@code email/<stem>}) maps to {@code (template_key, language_locale)}: the
 * {@code email/} prefix is stripped to the key (canonicalized lower-case to
 * match the aggregate) and the locale is the single send-path locale every file
 * default is keyed under. The override applies uniformly by
 * {@code (tenant, key, locale)} for every template — the legacy quirk that
 * dropped {@code clubId} on three senders is not reproduced.
 */
@Component
public class EmailTemplateDbResolver implements ITemplateResolver {

    private static final String NAME = "emailTemplateDbResolver";

    private final EmailTemplateRepository overrides;

    public EmailTemplateDbResolver(EmailTemplateRepository overrides) {
        this.overrides = overrides;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Integer getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public @Nullable TemplateResolution resolveTemplate(
            IEngineConfiguration configuration,
            @Nullable String ownerTemplate,
            String template,
            @Nullable Map<String, Object> templateResolutionAttributes) {
        if (!template.startsWith(TemplatedMailService.EMAIL_TEMPLATE_PREFIX)) {
            return null;
        }
        String key = template
                .substring(TemplatedMailService.EMAIL_TEMPLATE_PREFIX.length())
                .strip()
                .toLowerCase(Locale.ROOT);
        Optional<EmailTemplate> override =
                overrides.findByTemplateKeyAndLanguageLocale(key, EmailTemplateCatalog.defaultLocale());
        if (override.isEmpty()) {
            return null;
        }
        return new TemplateResolution(
                new StringTemplateResource(override.get().getBody()),
                TemplateMode.HTML,
                NonCacheableCacheEntryValidity.INSTANCE);
    }
}

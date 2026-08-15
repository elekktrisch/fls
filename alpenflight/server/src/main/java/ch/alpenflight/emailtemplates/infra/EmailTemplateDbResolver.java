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

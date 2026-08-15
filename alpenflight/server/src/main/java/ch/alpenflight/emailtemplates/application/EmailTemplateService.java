package ch.alpenflight.emailtemplates.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.emailtemplates.application.EmailTemplateCatalog.FileDefault;
import ch.alpenflight.emailtemplates.application.EmailTemplateDtos.EmailTemplateListItem;
import ch.alpenflight.emailtemplates.application.EmailTemplateDtos.EmailTemplateSaveRequest;
import ch.alpenflight.emailtemplates.domain.EmailTemplate;
import ch.alpenflight.emailtemplates.domain.EmailTemplateOverrideNotFoundException;
import ch.alpenflight.emailtemplates.domain.EmailTemplateRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EmailTemplateService {

    private static final String AUDIT_ENTITY_TYPE = "EmailTemplate";

    private final EmailTemplateRepository overrides;
    private final EmailTemplateCatalog catalog;
    private final AuditTrail auditTrail;

    public EmailTemplateService(EmailTemplateRepository overrides,
                                EmailTemplateCatalog catalog,
                                AuditTrail auditTrail) {
        this.overrides = overrides;
        this.catalog = catalog;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<EmailTemplateListItem> listTemplates() {
        Map<Identity, EmailTemplateListItem> union = new LinkedHashMap<>();
        for (FileDefault d : catalog.fileDefaults()) {
            union.put(Identity.of(d.templateKey(), d.languageLocale()),
                    EmailTemplateMapper.fromFileDefault(d));
        }
        for (EmailTemplate override : overrides.findAll()) {
            union.put(Identity.of(override.getTemplateKey(), override.getLanguageLocale()),
                    EmailTemplateMapper.fromOverride(override));
        }
        return List.copyOf(union.values());
    }

    public EmailTemplateListItem customize(String templateKey,
                                           String languageLocale,
                                           EmailTemplateSaveRequest req) {
        String key = canonical(templateKey);
        String locale = canonical(languageLocale);
        return overrides.findByTemplateKeyAndLanguageLocale(key, locale)
                .map(existing -> reviseExisting(existing, req))
                .orElseGet(() -> insertOverride(key, locale, req));
    }

    public void reset(String templateKey, String languageLocale) {
        String key = canonical(templateKey);
        String locale = canonical(languageLocale);
        EmailTemplate existing = overrides.findByTemplateKeyAndLanguageLocale(key, locale)
                .orElseThrow(() -> new EmailTemplateOverrideNotFoundException(key, locale));
        EmailTemplateListItem before = EmailTemplateMapper.fromOverride(existing);
        overrides.delete(existing);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, idOf(existing), before));
    }

    private EmailTemplateListItem insertOverride(String key, String locale, EmailTemplateSaveRequest req) {
        EmailTemplate created = overrides.save(
                EmailTemplate.customize(key, locale, req.subject(), req.body()));
        overrides.flush();
        EmailTemplateListItem after = EmailTemplateMapper.fromOverride(created);
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, idOf(created), after));
        return after;
    }

    private EmailTemplateListItem reviseExisting(EmailTemplate existing, EmailTemplateSaveRequest req) {
        EmailTemplateListItem before = EmailTemplateMapper.fromOverride(existing);
        existing.revise(req.subject(), req.body());
        EmailTemplate saved = overrides.save(existing);
        EmailTemplateListItem after = EmailTemplateMapper.fromOverride(saved);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, idOf(saved), before, after));
        return after;
    }

    private static java.util.UUID idOf(EmailTemplate t) {
        return java.util.Objects.requireNonNull(t.getId(),
                "EmailTemplate.id must be non-null after persist").value();
    }

    private static String canonical(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private record Identity(String templateKey, String languageLocale) {
        static Identity of(String key, String locale) {
            return new Identity(key, locale);
        }
    }
}

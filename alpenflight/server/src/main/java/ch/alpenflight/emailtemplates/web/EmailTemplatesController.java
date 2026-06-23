package ch.alpenflight.emailtemplates.web;

import ch.alpenflight.emailtemplates.application.EmailTemplateDtos.EmailTemplateListItem;
import ch.alpenflight.emailtemplates.application.EmailTemplateDtos.EmailTemplateSaveRequest;
import ch.alpenflight.emailtemplates.application.EmailTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for per-club email-template overrides. Per ADR 0005 the path is
 * plural kebab-case {@code /api/v1/email-templates}.
 *
 * <p>The list is a UNION of the S-082 Thymeleaf file defaults and the caller's
 * own-club override rows; where an override exists for a
 * {@code (template_key, language_locale)} it wins. Tenant scoping is structural
 * via Hibernate's {@code @TenantId} discriminator on {@code EmailTemplate.clubId},
 * so the union never carries another club's overrides.
 *
 * <p>Role gates mirror the legacy {@code EmailTemplatesController}: read +
 * write are CLUB_ADMINISTRATOR (own club only). A non-admin principal is denied
 * at the {@code @PreAuthorize} gate (403). Save upserts the override
 * (clone-on-customize); delete resets to the file default.
 */
@RestController
@RequestMapping(path = "/api/v1/email-templates", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "EmailTemplates", description = "Per-club overrides of transactional-email defaults.")
public class EmailTemplatesController {

    private final EmailTemplateService service;

    public EmailTemplatesController(EmailTemplateService service) {
        this.service = service;
    }

    @Operation(summary = "List file defaults UNIONed with the caller's own-club overrides (override wins).")
    @ApiResponse(responseCode = "200", description = "Array of union list items, each tagged FILE_DEFAULT or CLUB_OVERRIDE.")
    @GetMapping
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public List<EmailTemplateListItem> listTemplates() {
        return service.listTemplates();
    }

    @Operation(summary = "Customize a template: upsert the caller's override for the key+locale.")
    @ApiResponse(responseCode = "200", description = "Override inserted or updated in place.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @PutMapping(path = "/{templateKey}/{languageLocale}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public EmailTemplateListItem customize(@PathVariable String templateKey,
                                           @PathVariable String languageLocale,
                                           @Valid @RequestBody EmailTemplateSaveRequest req) {
        return service.customize(templateKey, languageLocale, req);
    }

    @Operation(summary = "Reset to default: delete the caller's override so the file default re-surfaces.")
    @ApiResponse(responseCode = "204", description = "Override deleted.")
    @ApiResponse(responseCode = "404", description = "No own-club override for that key+locale.")
    @DeleteMapping("/{templateKey}/{languageLocale}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> reset(@PathVariable String templateKey,
                                      @PathVariable String languageLocale) {
        service.reset(templateKey, languageLocale);
        return ResponseEntity.noContent().build();
    }
}

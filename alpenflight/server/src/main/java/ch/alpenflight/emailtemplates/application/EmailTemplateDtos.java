package ch.alpenflight.emailtemplates.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the email-templates REST surface. Records (immutable, explicit
 * field set); mass-assignment is structurally impossible because the
 * controller binds to the record, not to the {@link
 * ch.alpenflight.emailtemplates.domain.EmailTemplate} aggregate.
 *
 * <p>{@code clubId} (the {@code @TenantId} discriminator) is <strong>absent</strong>
 * from {@link EmailTemplateSaveRequest}: it is set by Hibernate's
 * {@code @TenantId} resolver from the JWT on persist (A04 mass-assignment
 * defense). {@code templateKey} + {@code languageLocale} are path variables,
 * never body fields, so the override identity is never client-forgeable.
 */
public final class EmailTemplateDtos {

    private EmailTemplateDtos() {}

    /**
     * Where a union-read row comes from: a S-082 Thymeleaf file shipped with
     * the app, or a per-club override row that suppresses the file default.
     */
    public enum TemplateSource {
        FILE_DEFAULT,
        CLUB_OVERRIDE
    }

    @Schema(description = "One entry of the union read: a file default or the club override that wins over it.")
    public record EmailTemplateListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String templateKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String languageLocale,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TemplateSource source,
            // File defaults carry no stored subject — null here; the editor pre-fills it.
            @Nullable String subject,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String body) {}

    @Schema(description = "Customization payload: upserts the caller's override for the path key+locale.")
    public record EmailTemplateSaveRequest(
            @NotBlank @Size(max = 500) String subject,
            @NotBlank String body) {}
}

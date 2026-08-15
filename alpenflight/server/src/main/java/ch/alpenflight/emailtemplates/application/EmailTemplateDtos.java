package ch.alpenflight.emailtemplates.application;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public final class EmailTemplateDtos {

    private EmailTemplateDtos() {}

    public enum TemplateSource {
        FILE_DEFAULT,
        CLUB_OVERRIDE
    }

    @Schema(description = "One entry of the union read: a file default or the club override that wins over it.")
    public record EmailTemplateListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String templateKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String languageLocale,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TemplateSource source,
            @Nullable String subject,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String body) {}

    @Schema(description = "Customization payload: upserts the caller's override for the path key+locale.")
    public record EmailTemplateSaveRequest(
            @NotBlank @Size(max = 500) String subject,
            @NotBlank String body) {}
}

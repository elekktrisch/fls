package ch.alpenflight.me.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "Account self-edit payload — caller's own User self-fields only.")
record MeProfileUpdateRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Display name.")
        @NotBlank @Size(max = 100) String friendlyName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Notification email (distinct from the Keycloak login email).")
        @NotBlank @Email @Size(max = 256) String notificationEmail,
        @Schema(description = "Contact phone number.")
        @Nullable @Size(max = 30) String phoneNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Preferred language id; must resolve to a known language.")
        @NotNull UUID languageId) {}

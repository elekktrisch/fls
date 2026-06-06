package ch.alpenflight.me.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Wire payload for {@code PATCH /api/v1/me/profile} — the caller-scoped
 * Account self-edit. Carries ONLY the User aggregate's self-editable fields.
 *
 * <p>Deliberately absent: {@code username} / {@code clubId} /
 * {@code keycloakSub} (identity-binding, immutable), {@code remarks}
 * (admin-only free-text — preserved unchanged on self-edit), {@code roles} /
 * {@code personId} / account-state (admin-only; the legacy profile endpoint
 * leaked role mutation and we must not). Binding to this record rather than
 * the {@link ch.alpenflight.users.domain.User} aggregate makes the
 * mass-assignment of those fields structurally impossible.
 */
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

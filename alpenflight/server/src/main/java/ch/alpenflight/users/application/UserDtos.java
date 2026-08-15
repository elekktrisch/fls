package ch.alpenflight.users.application;

import ch.alpenflight.platform.id.UserId;
import ch.alpenflight.users.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class UserDtos {

    private UserDtos() {}

    private static final String USERNAME_REGEX = "^[A-Za-z0-9._-]{3,256}$";

    @Schema(description = "User listitem projection.")
    public record UserListItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UserId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String friendlyName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String notificationEmail,
            @Nullable UUID personId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Roles read live from Keycloak realm-role mappings.")
                    List<Role> roles,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Keycloak `enabled` flag.")
                    boolean enabled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "True iff the KC user has unresolved required-actions (e.g. UPDATE_PASSWORD).")
                    boolean invitePending) {}

    @Schema(description = "User detail projection — fields from the local row + live KC role mappings.")
    public record UserResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UserId id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID clubId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String username,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String friendlyName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String notificationEmail,
            @Nullable String phoneNumber,
            @Nullable String remarks,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID languageId,
            @Nullable UUID personId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Role> roles,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean invitePending) {}

    @Schema(description = "Payload to invite a new user. Backend creates the Keycloak user, then inserts the FLS row in the same transaction; on DB failure the KC user is compensated-deleted.")
    public record UserInviteRequest(
            @NotBlank @Size(min = 3, max = 256)
                    @Pattern(regexp = USERNAME_REGEX, message = "username must be 3-256 alphanumerics / dot / underscore / dash")
                    String username,
            @NotBlank @Size(max = 100) String friendlyName,
            @NotBlank @Email @Size(max = 256) String notificationEmail,
            @Nullable @Size(max = 30) String phoneNumber,
            @Nullable @Size(max = 250) String remarks,
            @NotNull UUID languageId,
            @Nullable UUID personId,
            @NotNull Set<Role> roles) {}

    @Schema(description = "Payload to update a user — mutable fields only. Username / club / keycloakSub are identity-binding and not editable.")
    public record UserUpdateRequest(
            @NotBlank @Size(max = 100) String friendlyName,
            @NotBlank @Email @Size(max = 256) String notificationEmail,
            @Nullable @Size(max = 30) String phoneNumber,
            @Nullable @Size(max = 250) String remarks,
            @NotNull UUID languageId,
            @Nullable UUID personId,
            @NotNull Set<Role> roles) {}
}

package ch.alpenflight.me.web;

import ch.alpenflight.me.application.MeView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire shape for {@code GET /api/v1/me}. All ids are raw UUIDs (no external
 * prefix): the SPA passes {@code personId} verbatim into the
 * {@code GET /api/v1/flights?personId={uuid}} filter, so a round-trip
 * without string-munging keeps the SPA contract trivial.
 */
@Schema(description = "Authenticated-principal projection.")
public record MeResponse(
        @Schema(description = "Internal user.id (UUID). Null when the JWT sub matches no active user row.")
        @Nullable String id,
        @Schema(description = "Linked Person id (raw UUID). Null when user.person_id is null "
                + "(sysadmin / unmapped federated user).")
        @Nullable String personId,
        @Schema(description = "Caller's home club (UUID). Null when no user row matches the JWT sub.")
        @Nullable String clubId,
        @Schema(description = "Realm roles as carried by the JWT (no `ROLE_` prefix).")
        List<String> roles,
        @Schema(description = "First name. Resolved from the linked Person row when present, "
                + "otherwise from the JWT `given_name` claim.")
        @Nullable String firstName,
        @Schema(description = "Last name. Same resolution as firstName.")
        @Nullable String lastName,
        @Schema(description = "Notification email from the user row, or JWT `email` claim "
                + "when no user matches.")
        @Nullable String email,
        @Schema(description = "Username from the user row, or JWT `preferred_username` claim "
                + "when no user matches.")
        @Nullable String username) {

    static MeResponse from(MeView view) {
        return new MeResponse(
                view.userId() == null ? null : view.userId().toString(),
                view.personId() == null ? null : view.personId().toString(),
                view.clubId() == null ? null : view.clubId().toString(),
                view.roles(),
                view.firstName(),
                view.lastName(),
                view.email(),
                view.username());
    }
}

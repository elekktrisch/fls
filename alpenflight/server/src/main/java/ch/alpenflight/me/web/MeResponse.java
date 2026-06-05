package ch.alpenflight.me.web;

import ch.alpenflight.me.application.MeView;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.id.PersonId;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire shape for {@code GET /api/v1/me}. Identifiers carry their ADR 0019
 * external prefixes: {@code personId} as {@code pn-<uuid>} (matches
 * {@code FlightCrewItem.personId}, so the SPA compares {@code me.personId}
 * to {@code crew[].personId} directly and passes it into
 * {@code GET /api/v1/flights?personId=pn-<uuid>} without string-munging);
 * {@code clubId} as {@code clb-<uuid>} (matches {@code ClubDtos}). {@code id}
 * stays a raw UUID — no external-id form for the {@code user} row yet
 * (S-052 introduces one).
 */
@Schema(description = "Authenticated-principal projection.")
@JsonInclude(JsonInclude.Include.ALWAYS)
record MeResponse(
        @Schema(description = "Internal user.id (UUID). Null when the JWT sub matches no active user row.")
        @Nullable String id,
        @Schema(description = "Linked Person id, prefixed external form `pn-<uuid>`. Null when "
                + "user.person_id is null (sysadmin / unmapped federated user).")
        @Nullable String personId,
        @Schema(description = "Caller's home club, prefixed external form `clb-<uuid>`. Null when "
                + "no user row matches the JWT sub.")
        @Nullable String clubId,
        @Schema(description = "AlpenFlight realm roles (no `ROLE_` prefix). Filtered to the "
                + "canonical role catalog server-side; Keycloak built-ins (`uma_authorization`, "
                + "`offline_access`, `default-roles-*`) are stripped.")
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
        @Nullable String username,
        @Schema(description = "Account display name (`t_user.friendly_name`). The Account "
                + "self-edit form's initial value; null when no user row matches the JWT sub.")
        @Nullable String friendlyName,
        @Schema(description = "Contact phone (`t_user.phone_number`). Account self-edit field; "
                + "nullable.")
        @Nullable String phoneNumber,
        @Schema(description = "Preferred-language id (`t_user.language_id`, raw UUID). The "
                + "Account language-selector's current value; null when no user row matches.")
        @Nullable String languageId,
        @Schema(description = "BCP-47 code of `languageId` (e.g. `de`, `fr`). Lets the SPA flip "
                + "its active locale on a saved language change without a second round-trip.")
        @Nullable String languageCode) {

    static MeResponse from(MeView view) {
        return new MeResponse(
                view.userId() == null ? null : view.userId().toString(),
                view.personId() == null ? null : PersonId.of(view.personId()).toExternal(),
                view.clubId() == null ? null : ClubId.of(view.clubId()).toExternal(),
                view.roles(),
                view.firstName(),
                view.lastName(),
                view.email(),
                view.username(),
                view.friendlyName(),
                view.phoneNumber(),
                view.languageId() == null ? null : view.languageId().toString(),
                view.languageCode());
    }
}

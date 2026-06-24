package ch.alpenflight.joinrequests.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.joinrequests.domain.JoinRequestStatus;
import ch.alpenflight.users.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * DTOs for the join-request REST surface (S-178). Records (immutable, explicit
 * field set); the controller binds to the request record, not the aggregate,
 * so mass-assignment is structurally impossible.
 *
 * <p>The wire shape deliberately omits the PII the aggregate stamps from the
 * JWT ({@code email}, {@code friendlyName}) and the admin-decision fields the
 * pilot must not learn — a join-request response is principal- or admin-scoped
 * and carries only what each consumer needs.
 */
public final class JoinRequestDtos {

    private JoinRequestDtos() {}

    @Schema(description = "A pilot's request to join a club, keyed by the club's join code.")
    public record SubmitJoinRequest(
            @Schema(description = "The club's 8-char join code (S-177).")
            @NotBlank @Size(max = 64) String joinCode,
            @Schema(description = "Optional note to the admin (≤500 chars).")
            @Nullable @Size(max = 500) String note) {}

    /**
     * The admin's approve command. {@code roles} are the realm roles to grant
     * the new member (validated against {@link RoleAssignmentPolicy} — a
     * CLUB_ADMINISTRATOR may not escalate). {@code personId}, when present, links
     * the new {@code t_user} to an existing Person in the admin's tenant (a
     * cross-tenant id is a 409); when absent, a Person + PersonClub is
     * auto-created.
     */
    @Schema(description = "A CLUB_ADMINISTRATOR's approval of a join request.")
    public record ApproveJoinRequest(
            @Schema(description = "Realm roles to grant the new member.")
            @Nullable List<@NotBlank String> roles,
            @Schema(description = "Optional existing Person to link (must be in the admin's club).")
            @Nullable UUID personId) {

        /** The parsed, known realm roles; unknown wire names are dropped. */
        public Set<Role> parsedRoles() {
            if (roles == null) {
                return Set.of();
            }
            return roles.stream()
                    .map(Role::fromWire)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /** The admin's deny command — an optional reason shown to the pilot. */
    @Schema(description = "A CLUB_ADMINISTRATOR's denial of a join request.")
    public record DenyJoinRequest(
            @Schema(description = "Optional reason shown to the denied pilot (≤500 chars).")
            @Nullable @Size(max = 500) String reason) {}

    /**
     * The pilot's own view of their request — what {@code POST
     * /api/v1/join-requests}, {@code POST /{id}/withdraw}, and {@code GET
     * /api/v1/me/join-request} return. Carries the lifecycle status + the
     * admin's decision reason (shown to the denied pilot), never the
     * deciding-admin identity.
     *
     * <p>{@code clubName} / {@code city} / {@code logoUrl} are the requested
     * club's public-display projection — the pilot has no tenant yet, so the
     * {@code /join/pending} screen reads them off the request the pilot owns
     * rather than a cross-tenant club endpoint (S-178). They are resolved
     * server-side when the pilot's own request is returned and null on the
     * admin decision paths, which target the admin (who already has the club).
     */
    @Schema(description = "The pilot's own join-request projection.")
    public record JoinRequestResponse(
            UUID id,
            UUID clubId,
            @Nullable String clubName,
            @Nullable String city,
            @Nullable String logoUrl,
            JoinRequestStatus status,
            @Nullable String note,
            @Nullable String decisionReason,
            Instant createdOn,
            @Nullable Instant decidedOn) {

        /** Without the public club projection — admin decision paths target the admin. */
        public static JoinRequestResponse from(JoinRequest r) {
            return from(r, null);
        }

        /** With the requested club's public-display projection, for the request's owner. */
        public static JoinRequestResponse from(JoinRequest r, @Nullable Club club) {
            return new JoinRequestResponse(
                    r.getId(),
                    r.getClubId(),
                    club == null ? null : club.getClubname(),
                    club == null ? null : club.getCity(),
                    club == null ? null : club.getLogoUrl(),
                    r.getStatus(),
                    r.getNote(),
                    r.getDecisionReason(),
                    r.getCreatedOn(),
                    r.getDecidedOn());
        }
    }

    /**
     * One row of the admin pending-list ({@code GET
     * /api/v1/join-requests?status=pending}). Surfaces the pilot's identity
     * (email + friendly name) so the admin can recognise the requester — this
     * is the admin's own-tenant view, gated by CLUB_ADMINISTRATOR.
     */
    @Schema(description = "A pending join request as the club admin sees it.")
    public record PendingJoinRequestResponse(
            UUID id,
            UUID clubId,
            String email,
            String friendlyName,
            @Nullable String note,
            Instant createdOn) {

        public static PendingJoinRequestResponse from(JoinRequest r) {
            return new PendingJoinRequestResponse(
                    r.getId(),
                    r.getClubId(),
                    r.getEmail(),
                    r.getFriendlyName(),
                    r.getNote(),
                    r.getCreatedOn());
        }
    }
}

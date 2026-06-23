package ch.alpenflight.joinrequests.application;

import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.joinrequests.domain.JoinRequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
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
     * The pilot's own view of their request — what {@code POST
     * /api/v1/join-requests}, {@code POST /{id}/withdraw}, and {@code GET
     * /api/v1/me/join-request} return. Carries the lifecycle status + the
     * admin's decision reason (shown to the denied pilot), never the
     * deciding-admin identity.
     */
    @Schema(description = "The pilot's own join-request projection.")
    public record JoinRequestResponse(
            UUID id,
            UUID clubId,
            JoinRequestStatus status,
            @Nullable String note,
            @Nullable String decisionReason,
            Instant createdOn,
            @Nullable Instant decidedOn) {

        public static JoinRequestResponse from(JoinRequest r) {
            return new JoinRequestResponse(
                    r.getId(),
                    r.getClubId(),
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

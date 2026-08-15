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

public final class JoinRequestDtos {

    private JoinRequestDtos() {}

    @Schema(description = "A pilot's request to join a club, keyed by the club's join code.")
    public record SubmitJoinRequest(
            @Schema(description = "The club's 8-char join code (S-177).")
            @NotBlank @Size(max = 64) String joinCode,
            @Schema(description = "Optional note to the admin (≤500 chars).")
            @Nullable @Size(max = 500) String note) {}

    @Schema(description = "A CLUB_ADMINISTRATOR's approval of a join request.")
    public record ApproveJoinRequest(
            @Schema(description = "Realm roles to grant the new member.")
            @Nullable List<@NotBlank String> roles,
            @Schema(description = "Optional existing Person to link (must be in the admin's club).")
            @Nullable UUID personId) {

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

    @Schema(description = "A CLUB_ADMINISTRATOR's denial of a join request.")
    public record DenyJoinRequest(
            @Schema(description = "Optional reason shown to the denied pilot (≤500 chars).")
            @Nullable @Size(max = 500) String reason) {}

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

        public static JoinRequestResponse from(JoinRequest r) {
            return from(r, null);
        }

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

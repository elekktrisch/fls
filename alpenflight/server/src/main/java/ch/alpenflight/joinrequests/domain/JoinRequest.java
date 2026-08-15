package ch.alpenflight.joinrequests.domain;

import ch.alpenflight.audit.domain.AuditRedact;
import ch.alpenflight.platform.text.FreeText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_join_request")
public class JoinRequest {

    static final int MAX_TEXT_LENGTH = 500;

    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "keycloak_sub", nullable = false, updatable = false)
    private UUID keycloakSub;

    @AuditRedact
    @Column(name = "email", nullable = false, updatable = false)
    private String email;

    @AuditRedact
    @Column(name = "friendly_name", nullable = false, updatable = false)
    private String friendlyName;

    @TenantId
    @Column(name = "club_id", nullable = false, updatable = false)
    private UUID clubId;

    @AuditRedact
    @Column(name = "note", updatable = false)
    private @Nullable String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JoinRequestStatus status;

    @Column(name = "created_on", nullable = false, updatable = false)
    private Instant createdOn;

    @Column(name = "decided_on")
    private @Nullable Instant decidedOn;

    @Column(name = "decided_by_user_id")
    private @Nullable UUID decidedByUserId;

    @AuditRedact
    @Column(name = "decision_reason")
    private @Nullable String decisionReason;

    protected JoinRequest() {
        this.id = new UUID(0, 0);
        this.keycloakSub = new UUID(0, 0);
        this.email = "";
        this.friendlyName = "";
        this.clubId = new UUID(0, 0);
        this.status = JoinRequestStatus.PENDING;
        this.createdOn = Instant.EPOCH;
    }

    public static JoinRequest submit(UUID id, UUID keycloakSub, String email,
                                     String friendlyName, UUID clubId,
                                     @Nullable String note, Clock clock) {
        JoinRequest r = new JoinRequest();
        r.id = Objects.requireNonNull(id, "id");
        r.keycloakSub = Objects.requireNonNull(keycloakSub, "keycloakSub");
        r.email = requireNonBlank(email, "email");
        r.friendlyName = requireNonBlank(friendlyName, "friendlyName");
        r.clubId = Objects.requireNonNull(clubId, "clubId");
        r.note = FreeText.normalize(note, MAX_TEXT_LENGTH);
        r.status = JoinRequestStatus.PENDING;
        r.createdOn = Objects.requireNonNull(clock, "clock").instant();
        return r;
    }

    public void approve(UUID decidedByUserId, Clock clock) {
        decide(JoinRequestStatus.APPROVED, decidedByUserId, null, clock);
    }

    public void deny(@Nullable String reason, UUID decidedByUserId, Clock clock) {
        decide(JoinRequestStatus.DENIED, decidedByUserId,
                FreeText.normalize(reason, MAX_TEXT_LENGTH), clock);
    }

    public void withdraw(Clock clock) {
        decide(JoinRequestStatus.WITHDRAWN, null, null, clock);
    }

    private void decide(JoinRequestStatus target, @Nullable UUID decidedByUserId,
                        @Nullable String reason, Clock clock) {
        if (status != JoinRequestStatus.PENDING) {
            throw new IllegalJoinRequestStateException(
                    "Cannot transition from " + status + " to " + target
                            + " — only a pending request can be decided");
        }
        this.status = target;
        this.decidedOn = Objects.requireNonNull(clock, "clock").instant();
        this.decidedByUserId = decidedByUserId;
        this.decisionReason = reason;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    public UUID getId() {
        return id;
    }

    public UUID getKeycloakSub() {
        return keycloakSub;
    }

    public String getEmail() {
        return email;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    public UUID getClubId() {
        return clubId;
    }

    public @Nullable String getNote() {
        return note;
    }

    public JoinRequestStatus getStatus() {
        return status;
    }

    public Instant getCreatedOn() {
        return createdOn;
    }

    public @Nullable Instant getDecidedOn() {
        return decidedOn;
    }

    public @Nullable UUID getDecidedByUserId() {
        return decidedByUserId;
    }

    public @Nullable String getDecisionReason() {
        return decisionReason;
    }
}

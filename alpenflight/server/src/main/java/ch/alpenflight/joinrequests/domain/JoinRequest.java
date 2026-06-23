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

/**
 * Join-request aggregate root (S-178). One row per pilot's self-serve request to
 * join a club: the pilot {@link #submit submits}, an admin {@link #approve approves}
 * or {@link #deny denies}, or the pilot {@link #withdraw withdraws}.
 *
 * <p>Per ADR 0022 directive 2 the lifecycle FSM lives HERE, not the schema. A
 * request opens {@link JoinRequestStatus#PENDING} and moves once into exactly one
 * terminal state via a guarded mutator — any second transition is rejected with
 * {@link IllegalJoinRequestStateException}. The schema enforces only structure
 * (PK, the {@code club_id} FK, the {@code ux_join_request_alive} partial UNIQUE).
 *
 * <p>Tenant-scoped on {@code club_id} via Hibernate {@code @TenantId} (ADR 0008).
 * The submit path runs under a lookup-window context that fills this column for a
 * caller who has no tenant yet (T-05); every other read/write is tenant-filtered.
 *
 * <p>{@code keycloak_sub} / {@code email} / {@code friendlyName} come straight off
 * the authenticated principal at submit time — the caller has a Keycloak identity
 * but no {@code t_user} row. {@code note} and {@code decisionReason} are capped
 * free text (≤{@value #MAX_TEXT_LENGTH}) and {@code @AuditRedact} (hashed in the
 * audit ledger per S-027).
 */
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
        // JPA.
        this.id = new UUID(0, 0);
        this.keycloakSub = new UUID(0, 0);
        this.email = "";
        this.friendlyName = "";
        this.clubId = new UUID(0, 0);
        this.status = JoinRequestStatus.PENDING;
        this.createdOn = Instant.EPOCH;
    }

    /**
     * Files a new pending request. The id is caller-minted (UUIDv7 at the
     * service); identity, tenant, and timestamp are stamped once and never
     * mutated. {@code note} is normalized + length-capped here so an over-long
     * note never reaches persistence.
     *
     * @param id minted aggregate id (UUIDv7)
     * @param keycloakSub the authenticated principal's KC subject
     * @param email the principal's email (PII — redacted in audit)
     * @param friendlyName the principal's display name (PII — redacted in audit)
     * @param clubId the tenant — the club the code resolved to (S-177)
     * @param note optional pilot note (≤{@value #MAX_TEXT_LENGTH}), nullable
     * @param clock the submit-time source
     */
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

    /**
     * Admin approves a pending request. Records the deciding user + decision
     * time and moves to {@link JoinRequestStatus#APPROVED}. The cross-system
     * side effects (KC attribute, {@code t_user}, Person) are the application
     * service's job (T-06) — this only owns the state transition.
     *
     * @throws IllegalJoinRequestStateException if the request is not pending
     */
    public void approve(UUID decidedByUserId, Clock clock) {
        decide(JoinRequestStatus.APPROVED, decidedByUserId, null, clock);
    }

    /**
     * Admin denies a pending request with an optional reason (≤{@value
     * #MAX_TEXT_LENGTH}, shown to the pilot). Moves to {@link
     * JoinRequestStatus#DENIED}.
     *
     * @throws IllegalJoinRequestStateException if the request is not pending
     */
    public void deny(@Nullable String reason, UUID decidedByUserId, Clock clock) {
        decide(JoinRequestStatus.DENIED, decidedByUserId,
                FreeText.normalize(reason, MAX_TEXT_LENGTH), clock);
    }

    /**
     * The pilot withdraws their own pending request. Moves to {@link
     * JoinRequestStatus#WITHDRAWN}. A withdrawal records no deciding user (the
     * pilot acted on their own request) and starts NO cooldown — the pilot may
     * immediately re-submit.
     *
     * @throws IllegalJoinRequestStateException if the request is not pending
     */
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

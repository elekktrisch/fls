package ch.alpenflight.deployments.domain;

import ch.alpenflight.audit.domain.AuditRedact;
import ch.alpenflight.platform.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * Tenancy parent above {@code Club}. One legacy FLS upload bundle = one
 * Deployment containing 1..N Clubs (vision C31 + C34). Carries the
 * trial countdown, billing identifiers, the freemium plan, and the
 * lifecycle state — Club stays the {@code @TenantId} carrier so
 * cross-Club isolation inside one Deployment is preserved (ADR 0008).
 *
 * <p>Per ADR 0022 directive 2 the FSM lives here, not in the schema. The
 * legal-transition table is the {@link #LEGAL_TRANSITIONS} map; the
 * mutator methods ({@link #activateSubscription}, {@link #markPastDue},
 * {@link #cancel}, {@link #scheduleDelete}, {@link #expireTrial},
 * {@link #recoverFromDeletion}) re-validate inside the transaction and
 * throw {@link IllegalLifecycleTransitionException} on no edge. The schema
 * stores {@code lifecycle_state} as a string column — no CHECK enumerating
 * valid states.
 *
 * <p>{@link Plan} is stored, NOT generated (ADR 0022 D2 forbids generated
 * columns). Mutators write {@code plan} atomically with state on each
 * transition; {@link #assertPlanConsistent} verifies the derivation at the
 * end of every transition.
 *
 * <p>Audit emission rides {@link DeploymentLifecycleTransitioned} via
 * Spring Data's {@code @DomainEvents} publication on
 * {@link DeploymentRepository#save}. Actor + correlation-id come from the
 * audit listener's {@code SecurityContextHolder} / MDC; the event itself
 * stays tenant-context-free.
 *
 * <p>Optimistic locking via JPA {@link Version} — concurrent admin flips
 * or admin-flip-vs-cron races re-read the row inside the txn and one
 * commits; the other gets a stale-version retry.
 */
@Entity
@Table(name = "deployment")
public class Deployment {

    /**
     * Singleton shared-fixed Deployment hosting the demo-area Clubs. Created
     * by the V14 Flyway seed at a deterministic UUID so {@code S-135}
     * (sandbox-reset job) + {@code SandboxResetJob}'s
     * {@code @LifecycleStateFilter} reference it by constant.
     */
    public static final UUID SANDBOX_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Singleton operator-owned Deployment that hosts the legacy-imported
     * Clubs from pre-S-137 schema state. The V14 backfill UPDATE sets
     * every pre-existing {@code club.deployment_id} to this UUID; the
     * operator administers it via the admin endpoint.
     */
    public static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final int MAX_NAME_LENGTH = 200;

    /**
     * Legal source → targets edges. {@link LifecycleState#SANDBOX} is
     * absent from the keys (immutable). {@link LifecycleState#DELETING}
     * exposes the {@code → CANCELLED} edge so the admin endpoint can
     * rescue an accidentally-scheduled-delete Deployment within the
     * grace window (operator-grilled 2026-05-28).
     */
    private static final Map<LifecycleState, Set<LifecycleState>> LEGAL_TRANSITIONS;

    static {
        Map<LifecycleState, Set<LifecycleState>> edges = new EnumMap<>(LifecycleState.class);
        edges.put(LifecycleState.TRIAL,
                EnumSet.of(LifecycleState.ACTIVE, LifecycleState.DELETING));
        edges.put(LifecycleState.ACTIVE,
                EnumSet.of(LifecycleState.PAST_DUE, LifecycleState.CANCELLED));
        edges.put(LifecycleState.PAST_DUE,
                EnumSet.of(LifecycleState.ACTIVE, LifecycleState.CANCELLED));
        edges.put(LifecycleState.CANCELLED,
                EnumSet.of(LifecycleState.DELETING));
        edges.put(LifecycleState.DELETING,
                EnumSet.of(LifecycleState.CANCELLED));
        LEGAL_TRANSITIONS = Map.copyOf(edges);
    }

    @Id
    @UuidV7
    private @Nullable UUID id;

    @AuditRedact
    @Column(name = "name", nullable = false, length = MAX_NAME_LENGTH)
    private String name = "";

    // Final after startTrial / backfill — JPA enforces with updatable=false.
    // Transfer-deployment-to-other-user is an explicit non-feature (security
    // plan: future security-engineer review before any mutator lands).
    @AuditRedact
    @Column(name = "owner_keycloak_sub", nullable = false, updatable = false)
    private @Nullable UUID ownerKeycloakSub;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false, length = 32)
    private LifecycleState lifecycleState = LifecycleState.TRIAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 16)
    private Plan plan = Plan.FREE;

    @Column(name = "trial_started_at")
    private @Nullable Instant trialStartedAt;

    // PCI scope adjacency — S-145 owns the billing audit story.
    @AuditRedact
    @Column(name = "billing_customer_id", columnDefinition = "text")
    private @Nullable String billingCustomerId;

    @AuditRedact
    @Column(name = "billing_subscription_id", columnDefinition = "text")
    private @Nullable String billingSubscriptionId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_on", nullable = false, updatable = false)
    private @Nullable Instant createdOn;

    @Column(name = "modified_on", nullable = false)
    private @Nullable Instant modifiedOn;

    @Transient
    private final List<DeploymentLifecycleTransitioned> domainEvents = new ArrayList<>();

    protected Deployment() {
        // JPA.
    }

    /**
     * Provisions a new Deployment in {@link LifecycleState#TRIAL} on the
     * first successful ingest (S-138). Pure aggregate construction — safe
     * to call pre-commit; the audit row emits post-commit via the
     * {@code @TransactionalEventListener}.
     */
    public static Deployment startTrial(Clock clock, String name, UUID ownerKeycloakSub) {
        Deployment deployment = new Deployment();
        Instant now = Instant.now(clock);
        deployment.rename(name);
        if (ownerKeycloakSub == null) {
            throw new IllegalArgumentException("ownerKeycloakSub must not be null");
        }
        deployment.ownerKeycloakSub = ownerKeycloakSub;
        deployment.lifecycleState = LifecycleState.TRIAL;
        deployment.plan = derivedPlan(LifecycleState.TRIAL, Plan.FREE);
        deployment.trialStartedAt = now;
        deployment.createdOn = now;
        deployment.modifiedOn = now;
        deployment.assertPlanConsistent();
        deployment.recordTransition(null, LifecycleState.TRIAL, now);
        return deployment;
    }

    /** Transition {@code TRIAL → ACTIVE} or {@code PAST_DUE → ACTIVE}. */
    public void activateSubscription(String customerId, String subscriptionId, Clock clock) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be blank");
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new IllegalArgumentException("subscriptionId must not be blank");
        }
        transitionTo(LifecycleState.ACTIVE, clock);
        this.billingCustomerId = customerId;
        this.billingSubscriptionId = subscriptionId;
    }

    /** Transition {@code ACTIVE → PAST_DUE} on observed payment failure. */
    public void markPastDue(Clock clock) {
        transitionTo(LifecycleState.PAST_DUE, clock);
    }

    /** Transition to {@code CANCELLED} after dunning grace or operator action. */
    public void cancel(Clock clock) {
        transitionTo(LifecycleState.CANCELLED, clock);
    }

    /** Transition to {@code DELETING}. S-142 owns the cascade-cleanup worker. */
    public void scheduleDelete(Clock clock) {
        transitionTo(LifecycleState.DELETING, clock);
    }

    /**
     * Trial-expiry sweeper (S-142) flips {@code TRIAL → DELETING} at the
     * 72 h mark. Refuses if the source state isn't TRIAL — the sweeper
     * shouldn't be racing other transitions.
     */
    public void expireTrial(Clock clock) {
        if (this.lifecycleState != LifecycleState.TRIAL) {
            throw new IllegalLifecycleTransitionException(
                    this.lifecycleState,
                    LifecycleState.DELETING,
                    "expireTrial only valid from TRIAL");
        }
        transitionTo(LifecycleState.DELETING, clock);
    }

    /**
     * Admin rescue path inside the deletion grace window: flip
     * {@code DELETING → CANCELLED} so the cascade worker stops touching
     * this Deployment.
     */
    public void recoverFromDeletion(Clock clock) {
        if (this.lifecycleState != LifecycleState.DELETING) {
            throw new IllegalLifecycleTransitionException(
                    this.lifecycleState,
                    LifecycleState.CANCELLED,
                    "recoverFromDeletion only valid from DELETING");
        }
        transitionTo(LifecycleState.CANCELLED, clock);
    }

    /**
     * Admin endpoint entry point. Routes the target state to the matching
     * mutator OR throws {@link IllegalLifecycleTransitionException} when
     * the target isn't a single-step admin-driven edge (TRIAL is reached
     * only by {@link #startTrial}; SANDBOX is never reachable by a
     * transition; ACTIVE from anything but TRIAL/PAST_DUE goes through
     * {@link #activateSubscription} which needs billing IDs).
     */
    public void transitionByAdmin(LifecycleState target, Clock clock) {
        switch (target) {
            case PAST_DUE -> markPastDue(clock);
            case CANCELLED -> cancel(clock);
            case DELETING -> scheduleDelete(clock);
            case ACTIVE -> {
                if (this.lifecycleState == LifecycleState.DELETING) {
                    // grace-window rescue not allowed straight to ACTIVE
                    throw new IllegalLifecycleTransitionException(
                            this.lifecycleState, target,
                            "rescue from DELETING goes through CANCELLED");
                }
                transitionTo(LifecycleState.ACTIVE, clock);
            }
            case TRIAL, SANDBOX -> throw new IllegalLifecycleTransitionException(
                    this.lifecycleState, target,
                    "admin endpoint cannot flip to " + target);
        }
    }

    private void transitionTo(LifecycleState target, Clock clock) {
        if (this.lifecycleState == LifecycleState.SANDBOX) {
            throw new IllegalLifecycleTransitionException(
                    this.lifecycleState, target, "sandbox is immutable");
        }
        Set<LifecycleState> legal = LEGAL_TRANSITIONS.getOrDefault(this.lifecycleState, Set.of());
        if (!legal.contains(target)) {
            throw new IllegalLifecycleTransitionException(
                    this.lifecycleState, target,
                    "no edge from " + this.lifecycleState + " to " + target);
        }
        LifecycleState from = this.lifecycleState;
        Instant now = Instant.now(clock);
        this.lifecycleState = target;
        this.plan = derivedPlan(target, this.plan);
        this.modifiedOn = now;
        assertPlanConsistent();
        recordTransition(from, target, now);
    }

    private void rename(String newName) {
        String trimmed = newName == null ? "" : newName.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Deployment name must not be blank");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Deployment name exceeds %d characters".formatted(MAX_NAME_LENGTH));
        }
        this.name = trimmed;
    }

    private void recordTransition(@Nullable LifecycleState from,
                                  LifecycleState to,
                                  Instant occurredAt) {
        UUID assignedId = this.id;
        if (assignedId == null) {
            // startTrial fires before the @UuidV7 generator runs — the id is
            // populated by the BeforeExecutionGenerator at INSERT time. The
            // listener resolves the id from the event before writing; here
            // we substitute a sentinel that the listener replaces with the
            // saved aggregate's id post-publish. Practically: the post-save
            // @DomainEvents publication sees the populated id because save()
            // sets it before invoking the publisher.
            //
            // The aggregate hasn't been assigned an id yet, but Spring Data
            // publishes @DomainEvents AFTER save(), at which point the id is
            // populated. We capture the deferred reference here.
            assignedId = new UUID(0, 0);
        }
        domainEvents.add(new DeploymentLifecycleTransitioned(assignedId, from, to, occurredAt));
    }

    /**
     * Spring Data invokes this after {@link DeploymentRepository#save};
     * each transition produces one event, the listener writes the audit
     * row in a {@code REQUIRES_NEW} txn post-commit (S-027 listener
     * pattern).
     */
    @DomainEvents
    Collection<Object> domainEvents() {
        UUID assignedId = this.id;
        if (assignedId == null) {
            // Same defensive path as recordTransition — if the id is still
            // null at publication time, the events go out with the nil
            // sentinel; the post-save id resolution is handled by the
            // caller (S-138 re-reads). Today's flow always populates the
            // id before save returns.
            return List.copyOf(domainEvents);
        }
        // Repoint pending events to the populated id (the startTrial event
        // captured a nil sentinel when the aggregate was constructed pre-save).
        List<Object> resolved = new ArrayList<>(domainEvents.size());
        for (DeploymentLifecycleTransitioned event : domainEvents) {
            if (event.deploymentId().getMostSignificantBits() == 0L
                    && event.deploymentId().getLeastSignificantBits() == 0L) {
                resolved.add(new DeploymentLifecycleTransitioned(
                        assignedId, event.fromState(), event.toState(), event.occurredAt()));
            } else {
                resolved.add(event);
            }
        }
        return resolved;
    }

    @AfterDomainEventPublication
    void clearDomainEvents() {
        domainEvents.clear();
    }

    private static Plan derivedPlan(LifecycleState state, Plan previousPlan) {
        return switch (state) {
            case SANDBOX, CANCELLED -> Plan.FREE;
            case TRIAL, ACTIVE, PAST_DUE -> Plan.ACTIVE;
            // DELETING keeps the plan it held — body never runs for
            // DELETING (S-142 cascade owns the worker); plan reflects
            // what was held at scheduleDelete.
            case DELETING -> previousPlan;
        };
    }

    private void assertPlanConsistent() {
        Plan expected = derivedPlan(this.lifecycleState, this.plan);
        if (this.plan != expected) {
            throw new IllegalStateException(
                    "plan/state inconsistency: state=" + this.lifecycleState + " plan=" + this.plan);
        }
    }

    public @Nullable UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public @Nullable UUID getOwnerKeycloakSub() {
        return ownerKeycloakSub;
    }

    public LifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public Plan getPlan() {
        return plan;
    }

    public @Nullable Instant getTrialStartedAt() {
        return trialStartedAt;
    }

    public @Nullable String getBillingCustomerId() {
        return billingCustomerId;
    }

    public @Nullable String getBillingSubscriptionId() {
        return billingSubscriptionId;
    }

    public long getVersion() {
        return version;
    }

    public @Nullable Instant getCreatedOn() {
        return createdOn;
    }

    public @Nullable Instant getModifiedOn() {
        return modifiedOn;
    }

    /**
     * Test seam for {@link DeploymentTest} — the unit test asserts
     * emitted-event content without going through the JPA save() that
     * normally drives publication. Package-private on purpose.
     */
    List<DeploymentLifecycleTransitioned> domainEventsForTest() {
        return List.copyOf(domainEvents);
    }

    /**
     * Test seam producing a Deployment fixed at {@link LifecycleState#SANDBOX}
     * for the sandbox-as-source test cases. Bypasses {@link #startTrial} —
     * the production path constructs sandbox only via the V14 Flyway seed.
     */
    static Deployment sandboxFixture() {
        Deployment deployment = new Deployment();
        deployment.name = "sandbox";
        deployment.ownerKeycloakSub = new UUID(0L, 0L);
        deployment.lifecycleState = LifecycleState.SANDBOX;
        deployment.plan = Plan.FREE;
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        deployment.createdOn = now;
        deployment.modifiedOn = now;
        deployment.id = SANDBOX_ID;
        return deployment;
    }
}

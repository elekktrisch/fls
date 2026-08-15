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

@Entity
@Table(name = "t_deployment")
public class Deployment {

    public static final UUID SANDBOX_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final int MAX_NAME_LENGTH = 200;

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

    @AuditRedact
    @Column(name = "idempotency_key", updatable = false)
    private @Nullable UUID idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "kc_state", nullable = false, length = 16)
    private KeycloakReconcileState keycloakState = KeycloakReconcileState.PENDING;

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

    private record PendingTransition(@Nullable LifecycleState fromState,
                                     LifecycleState toState,
                                     java.time.Instant occurredAt) {}

    @Transient
    private final List<PendingTransition> pendingTransitions = new ArrayList<>();

    protected Deployment() {
    }

    public static Deployment startTrial(Clock clock, String name, UUID ownerKeycloakSub) {
        if (ownerKeycloakSub == null) {
            throw new IllegalArgumentException("ownerKeycloakSub must not be null");
        }
        Deployment deployment = new Deployment();
        deployment.rename(name);
        deployment.ownerKeycloakSub = ownerKeycloakSub;
        Instant now = Instant.now(clock);
        deployment.lifecycleState = LifecycleState.TRIAL;
        deployment.plan = derivedPlan(LifecycleState.TRIAL, Plan.FREE);
        deployment.trialStartedAt = now;
        deployment.createdOn = now;
        deployment.modifiedOn = now;
        deployment.recordTransition(null, LifecycleState.TRIAL, now);
        return deployment;
    }

    public void bindIdempotencyKey(UUID idempotencyKey) {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotencyKey must not be null");
        }
        if (this.idempotencyKey != null) {
            if (this.idempotencyKey.equals(idempotencyKey)) {
                return;
            }
            throw new IllegalStateException(
                    "Deployment is already bound to idempotency key " + this.idempotencyKey
                            + "; refusing to rebind to " + idempotencyKey);
        }
        this.idempotencyKey = idempotencyKey;
    }

    public boolean isKeycloakPending() {
        return this.keycloakState == KeycloakReconcileState.PENDING;
    }

    public void markKeycloakReady() {
        this.keycloakState = KeycloakReconcileState.READY;
    }

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

    public void markPastDue(Clock clock) {
        transitionTo(LifecycleState.PAST_DUE, clock);
    }

    public void cancel(Clock clock) {
        transitionTo(LifecycleState.CANCELLED, clock);
    }

    public void scheduleDelete(Clock clock) {
        transitionTo(LifecycleState.DELETING, clock);
    }

    public void expireTrial(Clock clock) {
        if (this.lifecycleState != LifecycleState.TRIAL) {
            throw new IllegalLifecycleTransitionException(
                    this.lifecycleState,
                    LifecycleState.DELETING,
                    "expireTrial only valid from TRIAL");
        }
        transitionTo(LifecycleState.DELETING, clock);
    }

    public void recoverFromDeletion(Clock clock) {
        if (this.lifecycleState != LifecycleState.DELETING) {
            throw new IllegalLifecycleTransitionException(
                    this.lifecycleState,
                    LifecycleState.CANCELLED,
                    "recoverFromDeletion only valid from DELETING");
        }
        transitionTo(LifecycleState.CANCELLED, clock);
    }

    public void transitionByAdmin(LifecycleState target, Clock clock) {
        switch (target) {
            case PAST_DUE -> markPastDue(clock);
            case CANCELLED -> cancel(clock);
            case DELETING -> scheduleDelete(clock);
            case ACTIVE -> {
                if (this.lifecycleState == LifecycleState.DELETING) {
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
        pendingTransitions.add(new PendingTransition(from, to, occurredAt));
    }

    @DomainEvents
    Collection<Object> domainEvents() {
        UUID resolvedId = this.id;
        if (resolvedId == null) {
            throw new IllegalStateException(
                    "Deployment.id is null at domain-event publication time — "
                            + "save() must run before events are drained.");
        }
        List<Object> events = new ArrayList<>(pendingTransitions.size());
        for (PendingTransition pending : pendingTransitions) {
            events.add(new DeploymentLifecycleTransitioned(
                    resolvedId, pending.fromState(), pending.toState(), pending.occurredAt()));
        }
        return events;
    }

    @AfterDomainEventPublication
    void clearDomainEvents() {
        pendingTransitions.clear();
    }

    private static Plan derivedPlan(LifecycleState state, Plan previousPlan) {
        return switch (state) {
            case SANDBOX, CANCELLED -> Plan.FREE;
            case TRIAL, ACTIVE, PAST_DUE -> Plan.ACTIVE;
            case DELETING -> previousPlan;
        };
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

    public @Nullable UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public KeycloakReconcileState getKeycloakState() {
        return keycloakState;
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

    List<DeploymentLifecycleTransitioned> domainEventsForTest() {
        UUID effectiveId = this.id == null ? SANDBOX_ID : this.id;
        List<DeploymentLifecycleTransitioned> snapshots = new ArrayList<>(pendingTransitions.size());
        for (PendingTransition pending : pendingTransitions) {
            snapshots.add(new DeploymentLifecycleTransitioned(
                    effectiveId, pending.fromState(), pending.toState(), pending.occurredAt()));
        }
        return List.copyOf(snapshots);
    }

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

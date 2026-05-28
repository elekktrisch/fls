package ch.alpenflight.deployments.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.deployments.domain.DeploymentLifecycleTransitioned;
import ch.alpenflight.deployments.domain.LifecycleSnapshot;
import ch.alpenflight.deployments.domain.LifecycleState;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to {@link DeploymentLifecycleTransitioned} domain events
 * published by Spring Data's {@code @DomainEvents} processor on
 * {@code DeploymentRepository.save}. Emits one
 * {@code mutation_audit_event} row per transition via the
 * {@link AuditTrail} port — the S-027 listener handles the
 * {@code REQUIRES_NEW} txn + actor + tenant resolution on the publishing
 * thread.
 *
 * <p>Plain {@code @EventListener} (NOT {@code @TransactionalEventListener})
 * because the audit-trail port itself opens a fresh-tx listener on its
 * own event; double-deferring would push the audit row past the txn
 * boundary the actor resolver needs.
 *
 * <p>Payload: {@link LifecycleSnapshot} keyed by entity type
 * {@code "Deployment"}. The PiiRedactor's allow-list emits
 * {@code lifecycleState} + {@code plan}; {@code (none) → TRIAL} stores a
 * literal {@code null} for {@code before_state} per the refinement pin.
 * The Deployment {@code @Entity} fields ({@code name},
 * {@code ownerKeycloakSub}, billing IDs) are
 * {@code @AuditRedact}-annotated and never surface in the audit JSON.
 */
@Component
class LifecycleTransitionAuditListener {

    private static final String ENTITY_TYPE = "Deployment";

    private final AuditTrail auditTrail;

    LifecycleTransitionAuditListener(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @EventListener
    public void onLifecycleTransition(DeploymentLifecycleTransitioned event) {
        LifecycleSnapshot before = snapshot(event.fromState());
        LifecycleSnapshot after = snapshot(event.toState());
        auditTrail.record(
                AuditAction.STATE_TRANSITION,
                new AuditedTarget(ENTITY_TYPE, event.deploymentId(), before, after));
    }

    private static @Nullable LifecycleSnapshot snapshot(@Nullable LifecycleState state) {
        if (state == null) {
            // The (none) → TRIAL synthetic transition stores null before_state.
            // Pin: downstream consumers expect literal null, NOT "none".
            return null;
        }
        return new LifecycleSnapshot(state);
    }
}

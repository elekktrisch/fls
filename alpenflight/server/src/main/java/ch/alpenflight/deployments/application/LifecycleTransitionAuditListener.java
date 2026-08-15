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
            return null;
        }
        return new LifecycleSnapshot(state);
    }
}

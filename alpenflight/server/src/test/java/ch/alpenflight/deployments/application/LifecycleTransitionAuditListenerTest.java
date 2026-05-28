package ch.alpenflight.deployments.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.deployments.domain.DeploymentLifecycleTransitioned;
import ch.alpenflight.deployments.domain.LifecycleSnapshot;
import ch.alpenflight.deployments.domain.LifecycleState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * Unit-level shape contract for the audit emission path:
 *
 * <ul>
 *   <li>{@code (none) → TRIAL} produces a {@link AuditedTarget} with
 *       literal {@code null} {@code before} — pinned for downstream
 *       consumers.</li>
 *   <li>Mid-lifecycle transitions carry both before + after
 *       {@link LifecycleSnapshot}s.</li>
 *   <li>Action is always {@link AuditAction#STATE_TRANSITION}; entity
 *       type is always {@code "Deployment"}; entityId is the event's
 *       deployment id.</li>
 * </ul>
 */
class LifecycleTransitionAuditListenerTest {

    private final RecordingAuditTrail recorded = new RecordingAuditTrail();
    private final LifecycleTransitionAuditListener listener =
            new LifecycleTransitionAuditListener(recorded);

    @Test
    void initial_trial_records_null_before_state() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        listener.onLifecycleTransition(new DeploymentLifecycleTransitioned(
                id, null, LifecycleState.TRIAL, Instant.parse("2026-05-28T10:00:00Z")));

        assertThat(recorded.actions).containsExactly(AuditAction.STATE_TRANSITION);
        AuditedTarget target = recorded.targets.get(0);
        assertThat(target.entityType()).isEqualTo("Deployment");
        assertThat(target.entityId()).isEqualTo(id);
        assertThat(target.before()).isNull();
        assertThat(target.after()).isEqualTo(new LifecycleSnapshot(LifecycleState.TRIAL));
    }

    @Test
    void mid_lifecycle_records_both_snapshots() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        listener.onLifecycleTransition(new DeploymentLifecycleTransitioned(
                id, LifecycleState.ACTIVE, LifecycleState.PAST_DUE,
                Instant.parse("2026-05-28T11:00:00Z")));

        AuditedTarget target = recorded.targets.get(0);
        assertThat(target.before()).isEqualTo(new LifecycleSnapshot(LifecycleState.ACTIVE));
        assertThat(target.after()).isEqualTo(new LifecycleSnapshot(LifecycleState.PAST_DUE));
    }

    private static final class RecordingAuditTrail implements AuditTrail {
        final List<AuditAction> actions = new ArrayList<>();
        final List<AuditedTarget> targets = new ArrayList<>();

        @Override
        public void record(AuditAction action, AuditedTarget target) {
            actions.add(action);
            targets.add(target);
        }

        @Override
        public void recordFailed(AuditAction action,
                                 AuditedTarget target,
                                 int httpStatus,
                                 @Nullable String failureReason) {
            // Unused — lifecycle transitions emit success rows only via
            // the domain-event path. The synthetic-failure filter (S-027)
            // owns the recordFailed seam.
        }
    }
}

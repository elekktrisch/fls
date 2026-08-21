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
        assertThat(target.before())
                .as("downstream consumers read a literal null before-state for the first transition")
                .isNull();
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
        public void recordAnonymousPublicSubmission(AuditAction action,
                                                    AuditedTarget target,
                                                    String clientIp) {
            throw new AssertionError(
                    "a lifecycle transition has no anonymous submitter: " + action + " " + clientIp);
        }

        @Override
        public void recordFailed(AuditAction action,
                                 AuditedTarget target,
                                 int httpStatus,
                                 @Nullable String failureReason) {
            throw new AssertionError(
                    "lifecycle transitions emit success rows only: " + action + " " + failureReason);
        }
    }
}

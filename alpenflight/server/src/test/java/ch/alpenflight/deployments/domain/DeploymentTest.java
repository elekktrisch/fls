package ch.alpenflight.deployments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * FSM matrix for {@link Deployment}. The legal-transition table is the
 * spec; this test enumerates every (source, target) pair and asserts that
 * legal pairs land + emit a {@link DeploymentLifecycleTransitioned} event
 * and illegal pairs throw {@link IllegalLifecycleTransitionException}.
 *
 * <p>Per ADR 0022 directive 2 the FSM lives on the aggregate; the schema
 * stores only the resulting {@code lifecycle_state} string.
 */
class DeploymentTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-05-28T10:00:00Z"), ZoneOffset.UTC);

    private static final Set<List<LifecycleState>> LEGAL_PAIRS = Set.of(
            List.of(LifecycleState.TRIAL, LifecycleState.ACTIVE),
            List.of(LifecycleState.TRIAL, LifecycleState.DELETING),
            List.of(LifecycleState.ACTIVE, LifecycleState.PAST_DUE),
            List.of(LifecycleState.ACTIVE, LifecycleState.CANCELLED),
            List.of(LifecycleState.PAST_DUE, LifecycleState.ACTIVE),
            List.of(LifecycleState.PAST_DUE, LifecycleState.CANCELLED),
            List.of(LifecycleState.CANCELLED, LifecycleState.DELETING),
            List.of(LifecycleState.DELETING, LifecycleState.CANCELLED));

    @Test
    void startTrial_emits_null_to_trial_event_with_active_plan() {
        Deployment deployment = Deployment.startTrial(FIXED, "ZSV", OWNER);

        assertThat(deployment.getLifecycleState()).isEqualTo(LifecycleState.TRIAL);
        assertThat(deployment.getPlan()).isEqualTo(Plan.ACTIVE);
        assertThat(deployment.getOwnerKeycloakSub()).isEqualTo(OWNER);
        assertThat(deployment.getTrialStartedAt()).isEqualTo(Instant.parse("2026-05-28T10:00:00Z"));

        List<DeploymentLifecycleTransitioned> events = drainEvents(deployment);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).fromState()).isNull();
        assertThat(events.get(0).toState()).isEqualTo(LifecycleState.TRIAL);
    }

    @ParameterizedTest(name = "{0} -> {1} is legal")
    @MethodSource("legalPairs")
    void legal_transitions_move_state_and_emit_event(LifecycleState from, LifecycleState to) {
        Deployment deployment = inState(from);

        applyTransition(deployment, to);

        assertThat(deployment.getLifecycleState()).isEqualTo(to);
        List<DeploymentLifecycleTransitioned> events = drainEvents(deployment);
        assertThat(events).extracting(DeploymentLifecycleTransitioned::toState).contains(to);
        assertThat(events.get(events.size() - 1).fromState()).isEqualTo(from);
    }

    @ParameterizedTest(name = "{0} -> {1} is illegal")
    @MethodSource("illegalPairs")
    void illegal_transitions_throw(LifecycleState from, LifecycleState to) {
        Deployment deployment = inState(from);

        assertThatThrownBy(() -> applyTransition(deployment, to))
                .isInstanceOf(IllegalLifecycleTransitionException.class);
    }

    @ParameterizedTest(name = "sandbox -> {0} is immutable")
    @MethodSource("allTargets")
    void sandbox_is_immutable(LifecycleState target) {
        Deployment sandbox = inState(LifecycleState.SANDBOX);

        assertThatThrownBy(() -> applyTransition(sandbox, target))
                .isInstanceOf(IllegalLifecycleTransitionException.class);
    }

    @Test
    void plan_derives_from_state_after_transition() {
        Deployment d = inState(LifecycleState.TRIAL);
        assertThat(d.getPlan()).isEqualTo(Plan.ACTIVE);

        d.activateSubscription("cus_1", "sub_1", FIXED);
        assertThat(d.getPlan()).isEqualTo(Plan.ACTIVE);

        d.markPastDue(FIXED);
        assertThat(d.getPlan()).isEqualTo(Plan.ACTIVE);

        d.cancel(FIXED);
        assertThat(d.getPlan()).isEqualTo(Plan.FREE);
    }

    @Test
    void expireTrial_only_from_trial() {
        Deployment trial = inState(LifecycleState.TRIAL);
        trial.expireTrial(FIXED);
        assertThat(trial.getLifecycleState()).isEqualTo(LifecycleState.DELETING);

        Deployment active = inState(LifecycleState.ACTIVE);
        assertThatThrownBy(() -> active.expireTrial(FIXED))
                .isInstanceOf(IllegalLifecycleTransitionException.class);
    }

    @Test
    void activateSubscription_records_billing_ids() {
        Deployment d = inState(LifecycleState.TRIAL);
        d.activateSubscription("cus_X", "sub_X", FIXED);
        assertThat(d.getBillingCustomerId()).isEqualTo("cus_X");
        assertThat(d.getBillingSubscriptionId()).isEqualTo("sub_X");
    }

    static Stream<Arguments> legalPairs() {
        return LEGAL_PAIRS.stream().map(p -> Arguments.of(p.get(0), p.get(1)));
    }

    static Stream<Arguments> illegalPairs() {
        return Stream.of(LifecycleState.values())
                .filter(s -> s != LifecycleState.SANDBOX)
                .flatMap(from -> Stream.of(LifecycleState.values())
                        .filter(to -> to != LifecycleState.SANDBOX)
                        .filter(to -> from != to)
                        .filter(to -> !LEGAL_PAIRS.contains(List.of(from, to)))
                        .map(to -> Arguments.of(from, to)));
    }

    static Stream<Arguments> allTargets() {
        return Stream.of(LifecycleState.values())
                .filter(s -> s != LifecycleState.SANDBOX)
                .map(Arguments::of);
    }

    private static Deployment inState(LifecycleState target) {
        if (target == LifecycleState.SANDBOX) {
            return Deployment.sandboxFixture();
        }
        Deployment deployment = Deployment.startTrial(FIXED, "fixture", OWNER);
        if (target == LifecycleState.TRIAL) {
            return deployment;
        }
        deployment.activateSubscription("cus", "sub", FIXED);
        if (target == LifecycleState.ACTIVE) {
            return deployment;
        }
        if (target == LifecycleState.PAST_DUE) {
            deployment.markPastDue(FIXED);
            return deployment;
        }
        if (target == LifecycleState.CANCELLED) {
            deployment.cancel(FIXED);
            return deployment;
        }
        if (target == LifecycleState.DELETING) {
            deployment.cancel(FIXED);
            deployment.scheduleDelete(FIXED);
            return deployment;
        }
        throw new IllegalStateException("unreachable: " + target);
    }

    private static void applyTransition(Deployment deployment, LifecycleState target) {
        switch (target) {
            case ACTIVE -> deployment.activateSubscription("cus_t", "sub_t", FIXED);
            case PAST_DUE -> deployment.markPastDue(FIXED);
            case CANCELLED -> deployment.cancel(FIXED);
            case DELETING -> deployment.scheduleDelete(FIXED);
            case TRIAL, SANDBOX ->
                    throw new IllegalArgumentException("no direct transition to " + target);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<DeploymentLifecycleTransitioned> drainEvents(Deployment deployment) {
        return (List<DeploymentLifecycleTransitioned>) (List<?>)
                List.copyOf(deployment.domainEventsForTest());
    }
}

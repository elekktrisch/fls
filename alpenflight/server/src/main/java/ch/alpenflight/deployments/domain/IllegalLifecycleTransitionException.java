package ch.alpenflight.deployments.domain;

import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@link Deployment} when a caller asks for a state transition
 * the FSM does not permit (no edge in the legal-transition table, or the
 * source is {@link LifecycleState#SANDBOX} which is immutable). The
 * admin-endpoint exception handler translates this to HTTP 409
 * {@code conflict}.
 *
 * <p>Free of {@code @ResponseStatus} per ADR 0023 — the deployments-web
 * advice owns the HTTP mapping.
 */
public class IllegalLifecycleTransitionException extends RuntimeException {

    private final @Nullable LifecycleState fromState;
    private final LifecycleState targetState;

    public IllegalLifecycleTransitionException(@Nullable LifecycleState fromState,
                                               LifecycleState targetState,
                                               String reason) {
        super("Illegal lifecycle transition %s -> %s: %s"
                .formatted(fromState, targetState, reason));
        this.fromState = fromState;
        this.targetState = targetState;
    }

    public @Nullable LifecycleState getFromState() {
        return fromState;
    }

    public LifecycleState getTargetState() {
        return targetState;
    }
}

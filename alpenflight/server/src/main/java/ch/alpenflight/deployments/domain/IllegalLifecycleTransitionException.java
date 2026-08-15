package ch.alpenflight.deployments.domain;

import org.jspecify.annotations.Nullable;

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

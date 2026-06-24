package ch.alpenflight.joinrequests.application;

/**
 * Thrown when a submit carries a join code that resolves to no active club
 * (S-178). Translated to HTTP 404 by {@code JoinRequestExceptionHandler} —
 * the pilot is told to check the code with their club admin.
 */
public class UnknownJoinCodeException extends RuntimeException {

    public UnknownJoinCodeException() {
        super("No active club resolves to that join code");
    }
}

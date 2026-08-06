package ch.alpenflight.publicregistration.application;

import java.time.Duration;

/**
 * An anonymous public-registration request — a submit or one of the reads the
 * forms open with — was refused by {@link PublicRegistrationAbuseGuard}.
 * Carries the back-off the public form renders as a countdown; the
 * {@code PublicRegistrationExceptionHandler} maps it to 429 +
 * {@code Retry-After}. Free of Spring-web imports (ADR 0023).
 *
 * <p>The back-off floors at one second: a {@code Retry-After: 0} invites the
 * immediate retry the guard just refused.
 */
public class PublicRegistrationThrottledException extends RuntimeException {

    private final long retryAfterSeconds;

    public PublicRegistrationThrottledException(String message, Duration retryAfter) {
        super(message);
        this.retryAfterSeconds = Math.max(1L, (retryAfter.toMillis() + 999L) / 1000L);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

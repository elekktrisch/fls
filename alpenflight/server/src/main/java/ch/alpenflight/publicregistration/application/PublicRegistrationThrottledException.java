package ch.alpenflight.publicregistration.application;

import java.time.Duration;

public class PublicRegistrationThrottledException extends RuntimeException {

    private static final long MIN_RETRY_AFTER_SECONDS_SO_ZERO_NEVER_INVITES_AN_IMMEDIATE_RETRY = 1L;

    private final long retryAfterSeconds;

    public PublicRegistrationThrottledException(String message, Duration retryAfter) {
        super(message);
        this.retryAfterSeconds = Math.max(
                MIN_RETRY_AFTER_SECONDS_SO_ZERO_NEVER_INVITES_AN_IMMEDIATE_RETRY,
                (retryAfter.toMillis() + 999L) / 1000L);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

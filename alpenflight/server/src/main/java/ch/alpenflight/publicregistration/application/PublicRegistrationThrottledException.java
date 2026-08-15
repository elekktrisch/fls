package ch.alpenflight.publicregistration.application;

import java.time.Duration;

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

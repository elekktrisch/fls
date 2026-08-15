package ch.alpenflight.joinrequests.application;

import java.time.Duration;

public class SubmitThrottledException extends RuntimeException {

    private final long retryAfterSeconds;

    public SubmitThrottledException(String message, Duration retryAfter) {
        super(message);
        this.retryAfterSeconds = Math.max(0, (retryAfter.toMillis() + 999) / 1000);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

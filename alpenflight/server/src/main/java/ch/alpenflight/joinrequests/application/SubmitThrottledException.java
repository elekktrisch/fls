package ch.alpenflight.joinrequests.application;

import java.time.Duration;

public class SubmitThrottledException extends RuntimeException {

    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long ROUND_UP_TO_NEXT_WHOLE_SECOND_MILLIS = MILLIS_PER_SECOND - 1;

    private final long retryAfterSeconds;

    public SubmitThrottledException(String message, Duration retryAfter) {
        super(message);
        this.retryAfterSeconds = Math.max(0,
                (retryAfter.toMillis() + ROUND_UP_TO_NEXT_WHOLE_SECOND_MILLIS) / MILLIS_PER_SECOND);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

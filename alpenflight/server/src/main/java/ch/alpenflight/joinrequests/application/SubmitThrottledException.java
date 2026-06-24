package ch.alpenflight.joinrequests.application;

import java.time.Duration;

/**
 * Submit was refused by the abuse guard (S-178) — either the per-sub brute-force
 * rate limit (5 attempts / 15 min) or the 24h per-{@code (sub, club)} deny
 * cooldown. Carries the back-off the pilot SPA renders as a countdown
 * ({@code Retry-After}). Free of Spring-web imports (ADR 0023); the
 * {@code JoinRequestExceptionHandler} maps it to 429 + the header.
 */
public class SubmitThrottledException extends RuntimeException {

    private final long retryAfterSeconds;

    public SubmitThrottledException(String message, Duration retryAfter) {
        super(message);
        // Round up: a sub-second remainder still means "wait the next whole second".
        this.retryAfterSeconds = Math.max(0, (retryAfter.toMillis() + 999) / 1000);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

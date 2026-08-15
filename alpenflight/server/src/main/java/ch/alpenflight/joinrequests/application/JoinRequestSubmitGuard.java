package ch.alpenflight.joinrequests.application;

import ch.alpenflight.joinrequests.domain.JoinRequestRepository;
import ch.alpenflight.platform.tenancy.Tenants;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class JoinRequestSubmitGuard {

    static final int MAX_ATTEMPTS = 5;
    static final int WINDOW_MINUTES = 15;
    static final int COOLDOWN_HOURS = 24;

    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);
    private static final Duration COOLDOWN = Duration.ofHours(COOLDOWN_HOURS);

    private final JoinRequestRepository requests;
    private final Clock clock;

    private final Map<UUID, Deque<Instant>> attemptsBySub = new ConcurrentHashMap<>();

    public JoinRequestSubmitGuard(JoinRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    public void recordAndCheckRateLimit(UUID sub) {
        Instant now = clock.instant();
        Deque<Instant> window = attemptsBySub.computeIfAbsent(sub, k -> new ArrayDeque<>());
        synchronized (window) {
            pruneExpired(window, now);
            window.addLast(now);
            if (window.size() > MAX_ATTEMPTS) {
                Instant oldest = window.peekFirst();
                Duration retryAfter = Duration.between(now, oldest.plus(WINDOW));
                throw new SubmitThrottledException(
                        "Too many join attempts — try again later", retryAfter);
            }
        }
    }

    public void checkDenyCooldown(UUID sub, UUID clubId) {
        Instant now = clock.instant();
        Tenants.runAs(clubId, () -> requests.findLatestDeniedDecidedOn(sub, clubId))
                .ifPresent(deniedAt -> {
                    Instant cooldownEnds = deniedAt.plus(COOLDOWN);
                    if (now.isBefore(cooldownEnds)) {
                        throw new SubmitThrottledException(
                                "This club denied a recent request — try again later",
                                Duration.between(now, cooldownEnds));
                    }
                });
    }

    private static void pruneExpired(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
            window.removeFirst();
        }
    }
}

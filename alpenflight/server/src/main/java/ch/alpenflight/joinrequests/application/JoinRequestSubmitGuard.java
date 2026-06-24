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

/**
 * Abuse defence on the {@code POST /api/v1/join-requests} submit path (S-178,
 * T-07). Two independent guards, both surfaced as 429 + {@code Retry-After}:
 *
 * <ul>
 *   <li><b>Brute-force rate limit</b> — at most {@value #MAX_ATTEMPTS} submit
 *       attempts per {@value #WINDOW_MINUTES} min per Keycloak sub. Counts every
 *       attempt (valid, unknown-code, already-member alike) so a pilot can't
 *       grind join codes. Held in a per-sub in-memory sliding window.</li>
 *   <li><b>Deny cooldown</b> — once a {@code (sub, club)} pair is DENIED, that
 *       pair may not re-submit for {@value #COOLDOWN_HOURS}h. DERIVED from the
 *       most-recent DENIED {@code decided_on} in {@code t_join_request} — no
 *       separate cooldown table (ADR 0022 §2: the window decision is Java, the
 *       schema only stores the decision time). Keyed on {@code (sub, club)}, not
 *       on the join code, so it survives a code rotation: a rotated code resolves
 *       to the same club, so the same cooldown still applies. A WITHDRAWN row is
 *       not DENIED, so a withdraw starts no cooldown.</li>
 * </ul>
 *
 * <h2>Multi-instance caveat</h2>
 *
 * <p>The brute-force window is per-process in-memory, so an N-instance
 * deployment tolerates up to {@code MAX_ATTEMPTS × N} attempts / window globally
 * (each instance counts only the requests it served). AlpenFlight runs
 * single-VPS (S-178), so this is acceptable; a horizontal-scale lift would move
 * the window to a shared cache (Redis / Bucket4j-distributed) — the guard is the
 * one seam to swap. The deny cooldown has no such caveat: it derives from the
 * shared DB, so it is correct across instances.
 */
@Component
public class JoinRequestSubmitGuard {

    static final int MAX_ATTEMPTS = 5;
    static final int WINDOW_MINUTES = 15;
    static final int COOLDOWN_HOURS = 24;

    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);
    private static final Duration COOLDOWN = Duration.ofHours(COOLDOWN_HOURS);

    private final JoinRequestRepository requests;
    private final Clock clock;

    /** Per-sub attempt timestamps, newest last; pruned to the live window on touch. */
    private final Map<UUID, Deque<Instant>> attemptsBySub = new ConcurrentHashMap<>();

    public JoinRequestSubmitGuard(JoinRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    /**
     * Records this submit attempt for {@code sub} and enforces the brute-force
     * window. Call FIRST in the submit path, before the code resolves — an
     * unknown-code probe still counts as an attempt.
     *
     * @throws SubmitThrottledException 429 — more than {@value #MAX_ATTEMPTS}
     *     attempts in the last {@value #WINDOW_MINUTES} min for this sub
     */
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

    /**
     * Enforces the 24h deny cooldown for {@code (sub, clubId)}. Call AFTER the
     * code resolves to a club, so a rotated code still maps to the same cooldown.
     * Reads under the resolved club's tenant scope because {@code JoinRequest} is
     * {@code @TenantId}-bound and the caller has no tenant yet.
     *
     * @throws SubmitThrottledException 429 — a DENIED row for this pair decided
     *     less than {@value #COOLDOWN_HOURS}h ago
     */
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

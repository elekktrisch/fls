package ch.alpenflight.publicregistration.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Abuse defence on the anonymous registration submits. There is no principal to
 * key on, so the source identity is the client IP, and two sliding windows run
 * off it — both surfaced as 429 + {@code Retry-After}:
 *
 * <ul>
 *   <li><b>Per (IP, club)</b> — at most {@value #MAX_ATTEMPTS_PER_CLUB} attempts
 *       per {@value #WINDOW_MINUTES} min. This is the anti-spam limit: bulk
 *       {@code Person} creation at one club from one source.</li>
 *   <li><b>Per IP across clubs</b> — at most {@value #MAX_ATTEMPTS_PER_SOURCE}
 *       attempts per {@value #WINDOW_MINUTES} min. Without it, slug enumeration
 *       is free — every fresh slug would open a fresh per-club bucket, so a
 *       prober would never trip the first limit and each probe would allocate a
 *       tracking entry from unauthenticated input.</li>
 * </ul>
 *
 * <p>Counting happens BEFORE the slug resolves, so an unknown-slug probe costs
 * the prober an attempt just like a real submission. The key is the raw slug
 * (case-folded), not the resolved club: at counting time there is no club yet.
 *
 * <p>The limits sit well above human use — a registrant submits once, plus a
 * retry or two after a validation error — because several genuine registrants
 * can share one source address behind NAT (a club open day on one venue WiFi).
 * The target is scripted bulk submission, not the sixth honest visitor.
 *
 * <h2>Multi-instance caveat</h2>
 *
 * <p>Both windows are per-process in-memory, so an N-instance deployment
 * tolerates N times the limits globally. AlpenFlight is single-VPS (ADR 0010),
 * so this is acceptable; a horizontal-scale lift moves the windows to a shared
 * cache and this class is the one seam to swap.
 */
@Component
public class PublicRegistrationSubmitGuard {

    static final int MAX_ATTEMPTS_PER_CLUB = 10;
    static final int MAX_ATTEMPTS_PER_SOURCE = 40;
    static final int WINDOW_MINUTES = 15;

    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);

    private final Clock clock;

    /** Attempt timestamps per source address; idle sources are swept out. */
    private final Map<String, SourceWindows> bySource = new ConcurrentHashMap<>();

    private final AtomicReference<Instant> nextSweep = new AtomicReference<>(Instant.EPOCH);

    public PublicRegistrationSubmitGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records this submit attempt and enforces both windows. Call FIRST in the
     * submit path, before the slug resolves.
     *
     * @throws PublicRegistrationThrottledException 429 — either window is full
     */
    public void recordAndCheck(String clientIp, String clubSlug) {
        Instant now = clock.instant();
        sweepIdleSources(now);
        String club = clubSlug.toLowerCase(Locale.ROOT);
        bySource.compute(clientIp, (key, existing) -> {
            SourceWindows windows = existing == null ? new SourceWindows() : existing;
            windows.record(club, now);
            return windows;
        });
    }

    /**
     * Drops sources with nothing left inside the live window. Anonymous callers
     * choose the keys, so the map cannot be allowed to grow for the lifetime of
     * the process. Time-gated because the scan is proportional to the number of
     * tracked sources.
     */
    private void sweepIdleSources(Instant now) {
        Instant due = nextSweep.get();
        if (now.isBefore(due) || !nextSweep.compareAndSet(due, now.plus(WINDOW))) {
            return;
        }
        Instant cutoff = now.minus(WINDOW);
        for (String source : List.copyOf(bySource.keySet())) {
            bySource.computeIfPresent(source,
                    (key, windows) -> windows.pruneExpired(cutoff) ? null : windows);
        }
    }

    /**
     * One source address's windows. Mutated only inside a {@code ConcurrentHashMap}
     * remapping function, which holds the bin lock — that serializes concurrent
     * attempts from the same source without a second lock.
     */
    private static final class SourceWindows {

        private final Deque<Instant> allClubs = new ArrayDeque<>();
        private final Map<String, Deque<Instant>> perClub = new HashMap<>();

        void record(String club, Instant now) {
            pruneExpired(now.minus(WINDOW));
            Deque<Instant> clubWindow = perClub.computeIfAbsent(club, key -> new ArrayDeque<>());
            allClubs.addLast(now);
            clubWindow.addLast(now);
            if (clubWindow.size() > MAX_ATTEMPTS_PER_CLUB) {
                throw throttled(clubWindow, now, "Too many registration attempts for this club");
            }
            if (allClubs.size() > MAX_ATTEMPTS_PER_SOURCE) {
                throw throttled(allClubs, now, "Too many registration attempts from this source");
            }
        }

        /** @return true when no attempt remains inside the live window */
        boolean pruneExpired(Instant cutoff) {
            prune(allClubs, cutoff);
            perClub.values().forEach(window -> prune(window, cutoff));
            perClub.values().removeIf(Deque::isEmpty);
            return allClubs.isEmpty();
        }

        private static void prune(Deque<Instant> window, Instant cutoff) {
            while (!window.isEmpty() && !Objects.requireNonNull(window.peekFirst()).isAfter(cutoff)) {
                window.removeFirst();
            }
        }

        private static PublicRegistrationThrottledException throttled(
                Deque<Instant> window, Instant now, String message) {
            Instant oldest = Objects.requireNonNull(window.peekFirst());
            return new PublicRegistrationThrottledException(
                    message, Duration.between(now, oldest.plus(WINDOW)));
        }
    }
}

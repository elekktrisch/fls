package ch.alpenflight.publicregistration.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Abuse defence on the whole anonymous public-registration surface — the two
 * submits and the two anonymous reads their forms open with. There is no
 * principal to key on, so the source identity is the client IP, and three
 * sliding windows run off it, each surfaced as 429 + {@code Retry-After}:
 *
 * <ul>
 *   <li><b>Submits, per (IP, club)</b> — at most {@value #MAX_ATTEMPTS_PER_CLUB}
 *       attempts per {@value #WINDOW_MINUTES} min. This is the anti-spam limit:
 *       bulk {@code Person} creation at one club from one source.</li>
 *   <li><b>Submits, per IP across clubs</b> — at most
 *       {@value #MAX_ATTEMPTS_PER_SOURCE} attempts per {@value #WINDOW_MINUTES}
 *       min. Without it, slug enumeration is free — every fresh slug would open
 *       a fresh per-club bucket, so a prober would never trip the first limit
 *       and each probe would allocate a tracking entry from unauthenticated
 *       input.</li>
 *   <li><b>Reads, DISTINCT clubs per IP</b> — at most
 *       {@value #MAX_CLUBS_READ_PER_SOURCE} different slugs per
 *       {@value #WINDOW_MINUTES} min. The anonymous club and discovery-day reads
 *       answer the same 200/403/404 oracle over the same keyspace as the
 *       submits, one database round-trip per well-formed slug; unbudgeted they
 *       are the free enumeration channel the per-source submit cap exists to
 *       close, and nothing terminates HTTP in front of the server to close it
 *       elsewhere ({@code ClientIpResolver}).</li>
 * </ul>
 *
 * <h2>Why the read budget counts reach, not volume</h2>
 *
 * <p>Re-reading a slug this source has already seen inside the window is free.
 * That is what keeps the budget off honest visitors: everyone arriving at one
 * club's registration URL reads that one slug, so a club open day behind a
 * single NAT spends exactly one unit no matter how many people load the page or
 * how often they reload it. Only reaching a club the source has not touched this
 * window costs — which is enumeration by definition, and a volume cap generous
 * enough to survive that open day would be far too high to bound it.
 *
 * <p>The reads are deliberately NOT charged to the submit budget: the read is
 * the first call of every visit, so one shared counter would let those same
 * visitors' page loads spend the budget their submissions then need.
 *
 * <p>Counting happens BEFORE the slug resolves, so an unknown-slug probe costs
 * the prober an attempt just like a real submission or a real page load. The key
 * is the raw slug (case-folded), not the resolved club: at counting time there is
 * no club yet.
 *
 * <p>The limits sit well above human use — a registrant submits once, plus a
 * retry or two after a validation error, and compares a handful of clubs at
 * most — because several genuine registrants can share one source address behind
 * NAT (a club open day on one venue WiFi). The target is scripted bulk
 * submission and scripted enumeration, not the sixth honest visitor.
 *
 * <h2>Multi-instance caveat</h2>
 *
 * <p>All three windows are per-process in-memory, so an N-instance deployment
 * tolerates N times the limits globally. AlpenFlight is single-VPS (ADR 0010),
 * so this is acceptable; a horizontal-scale lift moves the windows to a shared
 * cache and this class is the one seam to swap.
 */
@Component
public class PublicRegistrationAbuseGuard {

    static final int MAX_ATTEMPTS_PER_CLUB = 10;
    static final int MAX_ATTEMPTS_PER_SOURCE = 40;
    static final int MAX_CLUBS_READ_PER_SOURCE = 25;
    static final int WINDOW_MINUTES = 15;

    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);

    private final Clock clock;

    /** Attempt timestamps per source address; idle sources are swept out. */
    private final Map<String, SourceWindows> bySource = new ConcurrentHashMap<>();

    private final AtomicReference<Instant> nextSweep = new AtomicReference<>(Instant.EPOCH);

    public PublicRegistrationAbuseGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records this submit attempt and enforces both submit windows. Call FIRST
     * in the submit path, before the slug resolves.
     *
     * @throws PublicRegistrationThrottledException 429 — either window is full
     */
    public void recordSubmitAndCheck(String clientIp, String clubSlug) {
        record(clientIp, clubSlug, SourceWindows::recordSubmit);
    }

    /**
     * Records an anonymous read of {@code clubSlug} and enforces the read
     * budget. Call FIRST in the read path, before the slug resolves — a probe
     * that answers 404 has to cost what a real club costs, or the 404s are
     * themselves the unbudgeted oracle.
     *
     * @throws PublicRegistrationThrottledException 429 — this source has already
     *     reached {@value #MAX_CLUBS_READ_PER_SOURCE} distinct clubs inside the
     *     window
     */
    public void recordReadAndCheck(String clientIp, String clubSlug) {
        record(clientIp, clubSlug, SourceWindows::recordRead);
    }

    private void record(String clientIp, String clubSlug, WindowUpdate update) {
        Instant now = clock.instant();
        sweepIdleSources(now);
        String club = clubSlug.toLowerCase(Locale.ROOT);
        bySource.compute(clientIp, (key, existing) -> {
            SourceWindows windows = existing == null ? new SourceWindows() : existing;
            update.apply(windows, club, now);
            return windows;
        });
    }

    @FunctionalInterface
    private interface WindowUpdate {
        void apply(SourceWindows windows, String club, Instant now);
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

        /** Last read per DISTINCT slug — this source's enumeration reach. */
        private final Map<String, Instant> clubsRead = new HashMap<>();

        void recordSubmit(String club, Instant now) {
            pruneExpired(now.minus(WINDOW));
            Deque<Instant> clubWindow = perClub.computeIfAbsent(club, key -> new ArrayDeque<>());
            allClubs.addLast(now);
            clubWindow.addLast(now);
            if (clubWindow.size() > MAX_ATTEMPTS_PER_CLUB) {
                throw throttled(clubWindow.peekFirst(), now,
                        "Too many registration attempts for this club");
            }
            if (allClubs.size() > MAX_ATTEMPTS_PER_SOURCE) {
                throw throttled(allClubs.peekFirst(), now,
                        "Too many registration attempts from this source");
            }
        }

        /**
         * A slug already inside the window costs nothing, so a crowd behind one
         * address cannot spend the budget by loading the same page. The refusal
         * happens BEFORE the new slug is tracked, which also hard-bounds the map
         * an anonymous caller can grow at the limit rather than one past it.
         */
        void recordRead(String club, Instant now) {
            pruneExpired(now.minus(WINDOW));
            if (!clubsRead.containsKey(club) && clubsRead.size() >= MAX_CLUBS_READ_PER_SOURCE) {
                throw throttled(oldest(clubsRead.values()), now,
                        "Too many clubs looked up from this source");
            }
            clubsRead.put(club, now);
        }

        /** @return true when nothing this source did remains inside the live window */
        boolean pruneExpired(Instant cutoff) {
            prune(allClubs, cutoff);
            perClub.values().forEach(window -> prune(window, cutoff));
            perClub.values().removeIf(Deque::isEmpty);
            clubsRead.values().removeIf(seen -> !seen.isAfter(cutoff));
            return allClubs.isEmpty() && clubsRead.isEmpty();
        }

        private static void prune(Deque<Instant> window, Instant cutoff) {
            while (!window.isEmpty() && !Objects.requireNonNull(window.peekFirst()).isAfter(cutoff)) {
                window.removeFirst();
            }
        }

        private static Instant oldest(Collection<Instant> seen) {
            return seen.stream().min(Instant::compareTo)
                    .orElseThrow(() -> new IllegalStateException("a full window has an oldest entry"));
        }

        private static PublicRegistrationThrottledException throttled(
                @Nullable Instant oldest, Instant now, String message) {
            Instant first = Objects.requireNonNull(oldest);
            return new PublicRegistrationThrottledException(
                    message, Duration.between(now, first.plus(WINDOW)));
        }
    }
}

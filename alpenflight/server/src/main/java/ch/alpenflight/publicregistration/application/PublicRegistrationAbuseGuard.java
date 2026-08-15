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

@Component
public class PublicRegistrationAbuseGuard {

    // RENAME: MAX_ATTEMPTS_PER_CLUB -> MAX_SUBMITS_PER_SOURCE_AND_CLUB
    static final int MAX_ATTEMPTS_PER_CLUB = 10;
    // RENAME: MAX_ATTEMPTS_PER_SOURCE -> MAX_SUBMITS_PER_SOURCE_ACROSS_CLUBS
    static final int MAX_ATTEMPTS_PER_SOURCE = 40;
    static final int MAX_CLUBS_READ_PER_SOURCE = 25;
    static final int WINDOW_MINUTES = 15;

    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);

    private final Clock clock;

    private final Map<String, SourceWindowsGuardedByComputeBinLock> bySource =
            new ConcurrentHashMap<>();

    private final AtomicReference<Instant> nextSweep = new AtomicReference<>(Instant.EPOCH);

    public PublicRegistrationAbuseGuard(Clock clock) {
        this.clock = clock;
    }

    public void recordSubmitAndCheck(String clientIp, String clubSlug) {
        record(clientIp, clubSlug, SourceWindowsGuardedByComputeBinLock::recordSubmit);
    }

    public void recordReadAndCheck(String clientIp, String clubSlug) {
        record(clientIp, clubSlug, SourceWindowsGuardedByComputeBinLock::recordRead);
    }

    private void record(String clientIp, String clubSlug, WindowUpdate update) {
        Instant now = clock.instant();
        sweepIdleSources(now);
        String club = clubSlug.toLowerCase(Locale.ROOT);
        bySource.compute(clientIp, (key, existing) -> {
            SourceWindowsGuardedByComputeBinLock windows =
                    existing == null ? new SourceWindowsGuardedByComputeBinLock() : existing;
            update.apply(windows, club, now);
            return windows;
        });
    }

    @FunctionalInterface
    private interface WindowUpdate {
        void apply(SourceWindowsGuardedByComputeBinLock windows, String club, Instant now);
    }

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

    private static final class SourceWindowsGuardedByComputeBinLock {

        private final Deque<Instant> submitsAcrossAllClubs = new ArrayDeque<>();
        private final Map<String, Deque<Instant>> submitsPerClub = new HashMap<>();

        private final Map<String, Instant> distinctClubsReachedByReads = new HashMap<>();

        void recordSubmit(String club, Instant now) {
            pruneExpired(now.minus(WINDOW));
            Deque<Instant> clubWindow = submitsPerClub.computeIfAbsent(club, key -> new ArrayDeque<>());
            submitsAcrossAllClubs.addLast(now);
            clubWindow.addLast(now);
            if (clubWindow.size() > MAX_ATTEMPTS_PER_CLUB) {
                throw throttled(clubWindow.peekFirst(), now,
                        "Too many registration attempts for this club");
            }
            if (submitsAcrossAllClubs.size() > MAX_ATTEMPTS_PER_SOURCE) {
                throw throttled(submitsAcrossAllClubs.peekFirst(), now,
                        "Too many registration attempts from this source");
            }
        }

        void recordRead(String club, Instant now) {
            pruneExpired(now.minus(WINDOW));
            if (!distinctClubsReachedByReads.containsKey(club)
                    && distinctClubsReachedByReads.size() >= MAX_CLUBS_READ_PER_SOURCE) {
                throw throttled(oldest(distinctClubsReachedByReads.values()), now,
                        "Too many clubs looked up from this source");
            }
            distinctClubsReachedByReads.put(club, now);
        }

        boolean pruneExpired(Instant cutoff) {
            prune(submitsAcrossAllClubs, cutoff);
            submitsPerClub.values().forEach(window -> prune(window, cutoff));
            submitsPerClub.values().removeIf(Deque::isEmpty);
            distinctClubsReachedByReads.values().removeIf(seen -> !seen.isAfter(cutoff));
            return submitsAcrossAllClubs.isEmpty() && distinctClubsReachedByReads.isEmpty();
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

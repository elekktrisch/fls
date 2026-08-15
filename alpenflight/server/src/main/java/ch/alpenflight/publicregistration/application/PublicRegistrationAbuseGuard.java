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

    static final int MAX_ATTEMPTS_PER_CLUB = 10;
    static final int MAX_ATTEMPTS_PER_SOURCE = 40;
    static final int MAX_CLUBS_READ_PER_SOURCE = 25;
    static final int WINDOW_MINUTES = 15;

    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);

    private final Clock clock;

    private final Map<String, SourceWindows> bySource = new ConcurrentHashMap<>();

    private final AtomicReference<Instant> nextSweep = new AtomicReference<>(Instant.EPOCH);

    public PublicRegistrationAbuseGuard(Clock clock) {
        this.clock = clock;
    }

    public void recordSubmitAndCheck(String clientIp, String clubSlug) {
        record(clientIp, clubSlug, SourceWindows::recordSubmit);
    }

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

    private static final class SourceWindows {

        private final Deque<Instant> allClubs = new ArrayDeque<>();
        private final Map<String, Deque<Instant>> perClub = new HashMap<>();

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

        void recordRead(String club, Instant now) {
            pruneExpired(now.minus(WINDOW));
            if (!clubsRead.containsKey(club) && clubsRead.size() >= MAX_CLUBS_READ_PER_SOURCE) {
                throw throttled(oldest(clubsRead.values()), now,
                        "Too many clubs looked up from this source");
            }
            clubsRead.put(club, now);
        }

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

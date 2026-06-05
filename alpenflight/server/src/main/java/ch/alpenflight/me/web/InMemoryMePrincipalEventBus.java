package ch.alpenflight.me.web;

import ch.alpenflight.me.application.MePrincipalEventBus;
import ch.alpenflight.platform.scheduling.UnscopedScheduledJob;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory SSE transport for the principal-scoped event channel (S-176),
 * keyed by Keycloak {@code sub}. Implements the web-free producer port
 * {@link MePrincipalEventBus#publish} and adds the web-layer connection
 * lifecycle ({@link #register}) the {@link MeEventsController} hands back to
 * Spring MVC.
 *
 * <p><strong>Home = {@code me.web}.</strong> {@link SseEmitter} is a
 * Spring-web type, so the transport lives in the web layer (the ADR-0023
 * layering rule bans {@code org.springframework.web..} from
 * {@code application/}). Producers depend only on the web-free
 * {@link MePrincipalEventBus} publish port in {@code me.application}.
 *
 * <p><strong>State.</strong> {@code ConcurrentHashMap<sub, Deque<SseEmitter>>};
 * each per-{@code sub} {@link Deque} is guarded by synchronising on the deque
 * instance. A {@link Deque} (oldest at head) lets the cap evict the oldest
 * connection in O(1).
 *
 * <p><strong>Per-{@code sub} cap = {@value #MAX_CONNECTIONS_PER_SUB}.</strong>
 * On the (cap+1)-th register the <em>oldest</em> connection is completed and
 * dropped (carve choice: "close oldest", not "reject newest") — a user who
 * opens a 9th tab keeps the 8 most recent; the dropped tab silently
 * reconnects on the next interaction.
 *
 * <p><strong>Heartbeat.</strong> A single shared {@link Scheduled} sweep
 * (one scheduler thread, NOT a timer per emitter — heartbeat threads must
 * not accumulate) writes an SSE comment line to every live emitter every
 * {@code alpenflight.sse.heartbeat-interval-ms} (default 25s) to keep idle
 * connections alive through proxies. Marked {@link UnscopedScheduledJob}: it
 * operates on transport connections across all principals/tenants, not
 * per-(Deployment, Club) data — the S-137 scheduling guardrail requires the
 * explicit marker.
 */
@Component
class InMemoryMePrincipalEventBus implements MePrincipalEventBus {

    static final int MAX_CONNECTIONS_PER_SUB = 8;

    /**
     * Per-emitter async timeout. Generous (1h) — heartbeats keep a healthy
     * idle stream open; the timeout is the backstop that reclaims a stream
     * whose client vanished without an onError (the eviction wiring fires on
     * timeout). The browser EventSource layer reconnects transparently.
     */
    private static final long EMITTER_TIMEOUT_MS = 60L * 60L * 1000L;

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryMePrincipalEventBus.class);

    private final Map<String, Deque<SseEmitter>> connections = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    InMemoryMePrincipalEventBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Open a new live stream for the given principal. The returned emitter is
     * registered against {@code sub}, wired to self-evict on completion /
     * timeout / error, and subject to the per-{@code sub} connection cap
     * (oldest connection is closed on overflow). The controller hands this
     * emitter straight back to Spring MVC as the request's async result.
     */
    SseEmitter register(String sub) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Deque<SseEmitter> deque = connections.computeIfAbsent(sub, s -> new ArrayDeque<>());
        synchronized (deque) {
            while (deque.size() >= MAX_CONNECTIONS_PER_SUB) {
                SseEmitter oldest = deque.pollFirst();
                if (oldest != null) {
                    // Close-oldest cap: free the slot before the new tab joins.
                    oldest.complete();
                }
            }
            deque.addLast(emitter);
        }
        // Self-eviction: any terminal state removes the emitter from the deque
        // so a dead connection never receives a publish or heartbeat write.
        emitter.onCompletion(() -> evict(sub, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            evict(sub, emitter);
        });
        emitter.onError(e -> evict(sub, emitter));
        return emitter;
    }

    @Override
    public void publish(String sub, String kind, Object payload) {
        Deque<SseEmitter> deque = connections.get(sub);
        if (deque == null) {
            return;
        }
        String json = objectMapper.writeValueAsString(payload);
        List<SseEmitter> targets;
        synchronized (deque) {
            targets = new ArrayList<>(deque);
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name(kind).data(json));
            } catch (IOException | RuntimeException e) {
                // Broken pipe / client gone mid-publish — drop it. onError /
                // onCompletion may also fire; evict is idempotent.
                evict(sub, emitter);
            }
        }
    }

    @Scheduled(fixedRateString = "${alpenflight.sse.heartbeat-interval-ms:25000}")
    @UnscopedScheduledJob
    void heartbeat() {
        for (Map.Entry<String, Deque<SseEmitter>> entry : connections.entrySet()) {
            String sub = entry.getKey();
            List<SseEmitter> targets;
            synchronized (entry.getValue()) {
                targets = new ArrayList<>(entry.getValue());
            }
            for (SseEmitter emitter : targets) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException | RuntimeException e) {
                    evict(sub, emitter);
                }
            }
        }
    }

    private void evict(String sub, SseEmitter emitter) {
        Deque<SseEmitter> deque = connections.get(sub);
        if (deque == null) {
            return;
        }
        boolean empty;
        synchronized (deque) {
            deque.remove(emitter);
            empty = deque.isEmpty();
        }
        if (empty) {
            // Drop the empty deque so the map doesn't grow unbounded with
            // dead principals. computeIfPresent re-checks under the bin lock
            // to avoid racing a concurrent register that just re-populated it.
            connections.computeIfPresent(sub, (s, d) -> d.isEmpty() ? null : d);
        }
        LOG.trace("Evicted SSE emitter for sub {}", sub);
    }
}

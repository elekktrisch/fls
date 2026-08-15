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

@Component
class InMemoryMePrincipalEventBus implements MePrincipalEventBus {

    static final int MAX_CONNECTIONS_PER_SUB = 8;

    private static final long EMITTER_TIMEOUT_MS = 60L * 60L * 1000L;

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryMePrincipalEventBus.class);

    private final Map<String, Deque<SseEmitter>> connections = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    InMemoryMePrincipalEventBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    SseEmitter register(String sub) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Deque<SseEmitter> deque = connections.computeIfAbsent(sub, s -> new ArrayDeque<>());
        synchronized (deque) {
            while (deque.size() >= MAX_CONNECTIONS_PER_SUB) {
                SseEmitter oldest = deque.pollFirst();
                if (oldest != null) {
                    oldest.complete();
                }
            }
            deque.addLast(emitter);
        }
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
            connections.computeIfPresent(sub, (s, d) -> d.isEmpty() ? null : d);
        }
        LOG.trace("Evicted SSE emitter for sub {}", sub);
    }
}

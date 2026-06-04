package ch.alpenflight.me.application;

/**
 * Producer-side port for the principal-scoped Server-Sent Events channel
 * (S-176): the single fan-out point a producer module calls to push a typed
 * live update to a Keycloak {@code sub}. The dashboard's live-update channel
 * behind {@code GET /api/v1/me/events}.
 *
 * <p><strong>Web-type-free by design.</strong> This port deliberately
 * exposes only {@link #publish} and references no Spring-web transport type
 * ({@code SseEmitter} lives in {@code org.springframework.web..}, which the
 * ADR-0023 layering rule bans from {@code application/}). The connection
 * lifecycle ({@code register(sub) -> SseEmitter}, heartbeat, per-{@code sub}
 * cap) is a web-layer concern owned by the implementation in {@code me.web}.
 * Producers (e.g. the flight-create service, J-3 T-05) couple only to this
 * publish contract — the dependency direction is always
 * producer&rarr;{@code me}, never the reverse.
 *
 * <p><strong>Transport carve (J-3, pinned).</strong> Servlet-stack Spring
 * MVC {@code SseEmitter} (Servlet 3 async), <em>not</em> WebFlux/{@code Flux}.
 * In-memory only: no replay across restart, no cross-instance fan-out. Every
 * dashboard tile loads its state via a normal GET on first paint; SSE is the
 * change overlay, not the source of truth.
 */
public interface MePrincipalEventBus {

    /**
     * Fan a typed event out to every live emitter currently registered for
     * {@code sub}. The payload is serialised to JSON and written as an SSE
     * event named {@code kind}. Emitters that fail the write are evicted.
     * A no-op when the principal has no open stream.
     *
     * @param sub     the Keycloak subject to deliver to
     * @param kind    the SSE event name (e.g. {@code "flight.created"})
     * @param payload the event body, serialised to JSON
     */
    void publish(String sub, String kind, Object payload);
}

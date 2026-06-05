package ch.alpenflight.me.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/me/events} — the principal-scoped Server-Sent Events
 * stream (S-176), the live-update channel for the dashboard.
 *
 * <p>Sits under the standard Bearer filter chain ({@code /api/v1/**} is
 * {@code .authenticated()} in {@code platform.security.SecurityConfig}), so
 * an anonymous / no-token request is rejected by the resource server before
 * reaching this method — no anonymous stream. The principal is bound from
 * the validated JWT; the stream is keyed by its {@code sub}, so a caller can
 * only ever subscribe to their own events.
 *
 * <p>Transport is Servlet-stack Spring MVC {@link SseEmitter} (carve, J-3) —
 * no WebFlux. The heartbeat + per-{@code sub} connection cap live in the
 * bus; this controller only opens the stream.
 */
@RestController
@Tag(name = "me", description = "Authenticated-principal view")
class MeEventsController {

    private final InMemoryMePrincipalEventBus eventBus;

    MeEventsController(InMemoryMePrincipalEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @GetMapping(path = "/api/v1/me/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Live event stream for the authenticated principal (Server-Sent "
            + "Events). Keyed by the JWT subject; in-memory, no replay across restart. "
            + "Heartbeat comment line every ~25s keeps idle connections alive through proxies.")
    SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        return eventBus.register(jwt.getSubject());
    }
}

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

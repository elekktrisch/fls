/**
 * Authenticated-principal view + the principal-scoped live-event channel.
 * The read endpoint {@link ch.alpenflight.me.web.MeController} at
 * {@code GET /api/v1/me} returns claims-derived data enriched with the
 * tenant-scoped Person link resolved via {@code platform.tenancy.UserPrincipalLookup};
 * {@link ch.alpenflight.me.web.MeEventsController} at {@code GET /api/v1/me/events}
 * opens the Server-Sent Events stream (S-176).
 *
 * <p>Layered per ADR 0023: {@code application} owns the read service +
 * DTOs + the web-free {@link ch.alpenflight.me.application.MePrincipalEventBus}
 * producer port, {@code web} owns the HTTP surface — including the SSE
 * transport ({@code InMemoryMePrincipalEventBus}), since {@code SseEmitter}
 * is a Spring-web type the layering rule keeps out of {@code application/}.
 * The module is intentionally thin — there is no aggregate; the read
 * response is a projection over the {@code user} + {@code t_person} rows
 * already managed by other modules, and the bus is dependency-free
 * transport infra.
 *
 * <p>Declared an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module so producer modules may call the bus's
 * {@link ch.alpenflight.me.application.MePrincipalEventBus#publish publish}
 * fan-out point (J-3 T-05 wires {@code flight.created} from the flights
 * module INTO this bus). The dependency direction is always
 * producer&rarr;{@code me}; the bus has no knowledge of any producer module.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package ch.alpenflight.me;

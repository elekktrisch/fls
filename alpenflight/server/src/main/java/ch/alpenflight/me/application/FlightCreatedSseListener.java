package ch.alpenflight.me.application;

import ch.alpenflight.flights.application.FlightCreatedEvent;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Translates the {@code flights} module's {@link FlightCreatedEvent} into a
 * {@code "flight.created"} Server-Sent Event on the creating principal's
 * {@link MePrincipalEventBus} stream (S-176, J-3 T-05) — the thinnest concrete
 * consumer that proves the live-update channel.
 *
 * <p><strong>Module boundary.</strong> This listener lives in {@code me} (the
 * SSE owner) and imports the {@code flights} published event — so the
 * dependency is {@code me}&rarr;{@code flights}, and {@code flights} stays
 * ignorant of {@code me}'s transport. Both modules are declared OPEN; this is
 * the modulith-idiomatic cross-module Spring event channel (ADR 0018), mirroring
 * the audit trail's {@code DeploymentLifecycleTransitioned} listener.
 *
 * <p><strong>AFTER_COMMIT.</strong> The SSE fires only once the flight is
 * durably committed (matching the audit AFTER_COMMIT listener) — a rolled-back
 * create publishes no nudge. The receiver sub was captured on the request
 * thread at publish time ({@link FlightCreatedEvent#creatorSub()}); we deliver
 * to that sub's open dashboards. A null sub (non-JWT / system create) is a
 * no-op — no principal to nudge.
 *
 * <p>The payload is a small {@code flightId} marker; the tile reloads its count
 * via its normal GET on receipt — SSE is the change overlay, not the source of
 * truth. {@link MePrincipalEventBus#publish} is a no-op when the principal has
 * no open stream.
 */
@Component
class FlightCreatedSseListener {

    /** SSE event name the FE subscribes to for the live-tile nudge. */
    static final String EVENT_KIND = "flight.created";

    private final MePrincipalEventBus bus;

    FlightCreatedSseListener(MePrincipalEventBus bus) {
        this.bus = bus;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFlightCreated(FlightCreatedEvent event) {
        String sub = event.creatorSub();
        if (sub == null || sub.isBlank()) {
            return;
        }
        bus.publish(sub, EVENT_KIND, Map.of("flightId", event.flightId().toString()));
    }
}

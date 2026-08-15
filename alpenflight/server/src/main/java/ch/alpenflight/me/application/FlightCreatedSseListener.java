package ch.alpenflight.me.application;

import ch.alpenflight.flights.application.FlightCreatedEvent;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class FlightCreatedSseListener {

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

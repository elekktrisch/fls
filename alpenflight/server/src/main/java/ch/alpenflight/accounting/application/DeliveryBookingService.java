package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryRepository;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.FlightId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DeliveryBookingService {

    private static final String AUDIT_ENTITY_TYPE = "Delivery";

    private final DeliveryRepository deliveries;
    private final FlightRepository flights;
    private final AuditTrail auditTrail;

    public DeliveryBookingService(DeliveryRepository deliveries,
                                  FlightRepository flights,
                                  AuditTrail auditTrail) {
        this.deliveries = deliveries;
        this.flights = flights;
        this.auditTrail = auditTrail;
    }

    public boolean book(UUID deliveryId, Instant deliveredAt, @Nullable String deliveryNumber) {
        Optional<Delivery> found = deliveries.findActiveById(deliveryId);
        if (found.isEmpty()) {
            return false;
        }
        Delivery delivery = found.get();
        DeliveryDtos.DeliveryDetail before = DeliveryDetailMapper.toDetail(delivery);

        delivery.book(deliveryNumber, deliveredAt);
        deliveries.save(delivery);

        bookFlights(delivery.getFlightId());

        auditTrail.record(AuditAction.STATE_TRANSITION,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, deliveryId, before,
                        DeliveryDetailMapper.toDetail(delivery)));
        return true;
    }

    private void bookFlights(@Nullable UUID flightId) {
        if (flightId == null) {
            return;
        }
        flightOf(flightId).ifPresent(flight -> {
            flight.bookDelivery();
            flights.save(flight);
            UUID towFlightId = flight.getTowFlightId();
            if (towFlightId != null) {
                flightOf(towFlightId).ifPresent(tow -> {
                    tow.bookDelivery();
                    flights.save(tow);
                });
            }
        });
    }

    private Optional<Flight> flightOf(UUID flightId) {
        return flights.findByIdWithCrew(FlightId.of(flightId));
    }
}

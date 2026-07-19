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

/**
 * The delivery-booking write path — the AlpenFlight port of legacy
 * {@code DeliveryService.SetDeliveryAsDelivered} ({@code DeliveryService.cs:328-369}).
 * It stamps the externally-supplied (Proffix) delivery number + delivery timestamp,
 * marks the delivery {@code Booked}, and flips the linked flight AND its tow into
 * {@code DeliveryBooked}.
 *
 * <p>Parity quirk preserved: an unknown {@code deliveryId} is NOT an error — it
 * returns {@code false} ({@code DeliveryService.cs:336}), the contract the external
 * booker polls against. Tenant scoping is tightened over legacy (which looked up by
 * the bare Guid): the {@code @TenantId} discriminator makes another club's delivery
 * invisible, so a cross-tenant id behaves exactly like an unknown one — {@code false}.
 *
 * <p>Booked is terminal: re-booking an already-booked delivery is rejected
 * ({@link ch.alpenflight.accounting.domain.DeliveryBookedTerminalException} → 409),
 * closing legacy's un-guarded re-stamp of a closed billing record.
 */
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

    /**
     * Books the delivery: stamps its number + delivered timestamp, marks it Booked,
     * and flips the linked flight (+ tow) to {@code DeliveryBooked}. Returns
     * {@code true} when a delivery was booked, {@code false} when no active delivery
     * with that id exists in the caller's tenant (parity — not an error).
     *
     * @throws ch.alpenflight.accounting.domain.DeliveryBookedTerminalException when
     *     the delivery is already booked (→ 409, no mutation)
     */
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

package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryDeletionConflictException;
import ch.alpenflight.accounting.domain.DeliveryRepository;
import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.security.CurrentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DeliveryDeletionService {

    private static final String AUDIT_ENTITY_TYPE = "Delivery";

    private final DeliveryRepository deliveries;
    private final FlightRepository flights;
    private final PersonFlightTimeCreditRepository credits;
    private final AuditTrail auditTrail;
    private final CurrentPrincipal currentPrincipal;
    private final Clock clock;

    public DeliveryDeletionService(DeliveryRepository deliveries,
                                   FlightRepository flights,
                                   PersonFlightTimeCreditRepository credits,
                                   AuditTrail auditTrail,
                                   CurrentPrincipal currentPrincipal,
                                   Clock clock) {
        this.deliveries = deliveries;
        this.flights = flights;
        this.credits = credits;
        this.auditTrail = auditTrail;
        this.currentPrincipal = currentPrincipal;
        this.clock = clock;
    }

    public void delete(UUID deliveryId) {
        Delivery delivery = deliveries.findActiveById(deliveryId)
                .orElseThrow(() -> new DeliveryNotFoundException(deliveryId));

        UUID flightId = delivery.getFlightId();
        if (flightId != null) {
            long sharing = deliveries.countActiveByFlightId(flightId);
            if (sharing > 1) {
                throw new DeliveryDeletionConflictException(deliveryId, flightId, sharing);
            }
        }

        Instant now = clock.instant();
        DeliveryDtos.DeliveryDetail before = DeliveryDetailMapper.toDetail(delivery);

        delivery.delete(currentPrincipal.userId().orElse(null), clock);
        deliveries.save(delivery);

        if (flightId != null) {
            resetFlights(flightId, now);
            reverseCredit(deliveryId, now);
        }

        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, deliveryId, before));
    }

    private void resetFlights(UUID flightId, Instant now) {
        flightOf(flightId).ifPresent(flight -> {
            flight.resetFromDeliveryPrepared(now);
            flights.save(flight);
            UUID towFlightId = flight.getTowFlightId();
            if (towFlightId != null) {
                flightOf(towFlightId).ifPresent(tow -> {
                    tow.resetFromDeliveryPrepared(now);
                    flights.save(tow);
                });
            }
        });
    }

    private Optional<Flight> flightOf(UUID flightId) {
        return flights.findByIdWithCrew(FlightId.of(flightId));
    }

    private void reverseCredit(UUID deliveryId, Instant now) {
        credits.findByBalancedDeliveryId(deliveryId).ifPresent(credit -> {
            Long currentBalance = credit.currentBalanceInSeconds();
            releaseCurrentBalanceRowBeforeInsertingItsSuccessor(credit);
            credit.appendReversal(deliveryId, currentBalance, now);
            credits.save(credit);
            credits.flush();
        });
    }

    private void releaseCurrentBalanceRowBeforeInsertingItsSuccessor(PersonFlightTimeCredit credit) {
        if (credit.releaseCurrent()) {
            credits.save(credit);
            credits.flush();
        }
    }
}

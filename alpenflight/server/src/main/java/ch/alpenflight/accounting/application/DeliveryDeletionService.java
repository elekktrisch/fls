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
import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery-delete write path — the AlpenFlight port of legacy
 * {@code DeliveryService.DeleteDelivery} ({@code DeliveryService.cs:1226-1289}). It
 * soft-deletes a Prepared delivery (+ items), resets the linked flight AND its tow
 * back to {@code Locked} so the next create run re-bills them, and reverses any
 * prepaid flight-time credit the create consumed (append-only compensating row).
 *
 * <p>Guard order mirrors legacy: role (enforced at the controller) → load the
 * delivery (missing / cross-tenant → 404) → reject when {@code >1} active delivery
 * shares the flight ({@code :1242}, a clean 409, no partial mutation) → mutate.
 *
 * <p>Persisting the flight + tow reset corrects the two reachable legacy bugs in the
 * buggy sibling {@code FlightService.DeleteDeliveriesAndUpdateProcessStatesOfFlight}
 * ({@code :1457-1493} — wrong tow target + never {@code SaveChanges}d). The clean
 * legacy path this ports already persists + resets the correct flights.
 */
@Service
@Transactional
public class DeliveryDeletionService {

    private static final String AUDIT_ENTITY_TYPE = "Delivery";

    private final DeliveryRepository deliveries;
    private final FlightRepository flights;
    private final PersonFlightTimeCreditRepository credits;
    private final AuditTrail auditTrail;
    private final UserPrincipalLookup principals;
    private final Clock clock;

    public DeliveryDeletionService(DeliveryRepository deliveries,
                                   FlightRepository flights,
                                   PersonFlightTimeCreditRepository credits,
                                   AuditTrail auditTrail,
                                   UserPrincipalLookup principals,
                                   Clock clock) {
        this.deliveries = deliveries;
        this.flights = flights;
        this.credits = credits;
        this.auditTrail = auditTrail;
        this.principals = principals;
        this.clock = clock;
    }

    /**
     * Deletes the delivery, resetting its flight (+ tow) to Locked and reversing the
     * consumed credit. Throws {@link DeliveryNotFoundException} (→ 404) when the id is
     * unknown or cross-tenant, and {@link DeliveryDeletionConflictException} (→ 409)
     * when more than one active delivery shares the flight — checked before any
     * mutation, so the reject leaves the data untouched.
     */
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

        delivery.delete(currentUserId(), clock);
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
            // Flush the un-current of the prior row before inserting the reversal —
            // the partial UNIQUE ux_pftc_transaction_current forbids two live current
            // rows and Hibernate would otherwise order the INSERT before the UPDATE.
            if (credit.releaseCurrent()) {
                credits.save(credit);
                credits.flush();
            }
            credit.appendReversal(deliveryId, currentBalance, now);
            credits.save(credit);
            credits.flush();
        });
    }

    private @Nullable UUID currentUserId() {
        Jwt jwt = currentJwt();
        return jwt == null ? null : principals.resolveUserIdFor(jwt).orElse(null);
    }

    private static @Nullable Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && auth.isAuthenticated()) {
            return jwtAuth.getToken();
        }
        return null;
    }
}

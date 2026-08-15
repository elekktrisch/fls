package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryDetail;
import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryPreparationException;
import ch.alpenflight.accounting.domain.DeliveryRepository;
import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.CreditConsumption;
import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.articles.domain.ArticleRepository;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.FlightId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DeliveryCreationService {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryCreationService.class);
    private static final String AUDIT_ENTITY_TYPE = "Delivery";
    private static final Duration ELIGIBILITY_AGE = Duration.ofDays(3);
    private static final Set<FlightAircraftType> BILLABLE_TYPES =
            Set.of(FlightAircraftType.GLIDER, FlightAircraftType.MOTOR);

    private final FlightRepository flights;
    private final AccountingDeliveryEngine engine;
    private final DeliveryRepository deliveries;
    private final ArticleRepository articles;
    private final PersonFlightTimeCreditRepository credits;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public DeliveryCreationService(FlightRepository flights,
                                   AccountingDeliveryEngine engine,
                                   DeliveryRepository deliveries,
                                   ArticleRepository articles,
                                   PersonFlightTimeCreditRepository credits,
                                   AuditTrail auditTrail,
                                   Clock clock) {
        this.flights = flights;
        this.engine = engine;
        this.deliveries = deliveries;
        this.articles = articles;
        this.credits = credits;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    public List<DeliveryDetail> createFromEligibleFlights() {
        Instant now = clock.instant();
        long nextBatchId = deliveries.findMaxBatchId() + 1;

        List<DeliveryDetail> created = new ArrayList<>();
        for (Flight flight : eligibleFlights(now)) {
            DeliveryDetail detail = createForFlight(flight, nextBatchId, now);
            if (detail != null) {
                created.add(detail);
                nextBatchId++;
            }
        }
        return created;
    }

    private @Nullable DeliveryDetail createForFlight(Flight flight, long batchId, Instant now) {
        UUID flightId = Objects.requireNonNull(flight.getId(), "an eligible flight has an id");
        UUID clubId = Objects.requireNonNull(flight.getOperatingClubId(), "an eligible flight is tenant-scoped");
        try {
            RuleBasedDeliveryDetails computed = engine.computeForFlight(flightId);
            if (computed.isDoNotInvoiceFlight()) {
                flight.excludeFromDeliveryProcess();
                flights.save(flight);
                return null;
            }

            Delivery delivery = Delivery.createFromEligibleFlight(
                    clubId, flightId, computed, batchId, this::resolveArticleId);
            Delivery saved = deliveries.save(delivery);
            UUID deliveryId = Objects.requireNonNull(saved.getId(), "a persisted delivery has an id");

            prepareFlights(flight, now);
            applyCreditConsumption(computed.creditConsumptions(), deliveryId, now);

            DeliveryDetail detail = DeliveryDetailMapper.toDetail(saved);
            auditTrail.record(AuditAction.CREATE,
                    AuditedTarget.created(AUDIT_ENTITY_TYPE, deliveryId, detail));
            return detail;
        } catch (DeliveryPreparationException e) {
            LOG.info("flight {} could not be prepared for delivery: {}", flightId, e.getMessage());
            flight.markDeliveryPreparationError();
            flights.save(flight);
            return null;
        }
    }

    private void prepareFlights(Flight flight, Instant now) {
        flight.prepareForDelivery(now);
        flights.save(flight);
        towOf(flight).ifPresent(tow -> {
            tow.prepareForDelivery(now);
            flights.save(tow);
        });
    }

    private Optional<Flight> towOf(Flight flight) {
        UUID towFlightId = flight.getTowFlightId();
        if (towFlightId == null) {
            return Optional.empty();
        }
        return flights.findByIdWithCrew(FlightId.of(towFlightId));
    }

    private void applyCreditConsumption(List<CreditConsumption> consumptions, UUID deliveryId, Instant now) {
        for (CreditConsumption consumption : consumptions) {
            credits.findById(consumption.creditId()).ifPresent(credit -> {
                Long oldBalance = credit.currentBalanceInSeconds();
                boolean wasUnlimited = credit.hasCurrentBalance()
                        ? credit.currentTransaction().orElseThrow().isNoFlightTimeLimit()
                        : credit.isNoFlightTimeLimit();
                if (credit.releaseCurrent()) {
                    credits.save(credit);
                    credits.flush();
                }
                credit.appendConsumption(
                        consumption.consumedSeconds(), oldBalance, wasUnlimited, deliveryId, now);
                credits.save(credit);
                credits.flush();
            });
        }
    }

    private @Nullable UUID resolveArticleId(String articleNumber) {
        return articles.findActiveByNumber(articleNumber)
                .map(Article::getId)
                .map(id -> id == null ? null : id.value())
                .orElse(null);
    }

    private List<Flight> eligibleFlights(Instant now) {
        Instant cutoff = now.minus(ELIGIBILITY_AGE);
        return flights.findByProcessStateId(FlightProcessState.LOCKED.id()).stream()
                .filter(f -> BILLABLE_TYPES.contains(f.getFlightAircraftType()))
                .filter(f -> isOldEnough(f.getCreatedOn(), cutoff))
                .sorted(Comparator.comparing(Flight::getStartDateTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static boolean isOldEnough(@Nullable Instant createdOn, Instant cutoff) {
        return createdOn != null && !createdOn.isAfter(cutoff);
    }
}

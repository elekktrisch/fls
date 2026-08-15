package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.application.RuleFilterLoader.LoadedFilters;
import ch.alpenflight.accounting.domain.AccountingRuleMatcher;
import ch.alpenflight.accounting.domain.DeliveryItemPipeline;
import ch.alpenflight.accounting.domain.DeliveryItemPipeline.TowInput;
import ch.alpenflight.accounting.domain.IgnoreFlightStage;
import ch.alpenflight.accounting.domain.MatchableFlight;
import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.accounting.domain.RecipientStage;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightNotFoundException;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountingDeliveryEngine {

    private final FlightRepository flights;
    private final MatchableFlightResolver flightResolver;
    private final RuleFilterLoader filterLoader;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final PersonFlightTimeCreditRepository credits;

    private final IgnoreFlightStage ignoreFlight;
    private final RecipientStage recipient;
    private final DeliveryItemPipeline pipeline;

    public AccountingDeliveryEngine(FlightRepository flights,
                                    MatchableFlightResolver flightResolver,
                                    RuleFilterLoader filterLoader,
                                    ClubTenantIdentifierResolver tenantResolver,
                                    PersonFlightTimeCreditRepository credits) {
        this.flights = flights;
        this.flightResolver = flightResolver;
        this.filterLoader = filterLoader;
        this.tenantResolver = tenantResolver;
        this.credits = credits;
        AccountingRuleMatcher matcher = new AccountingRuleMatcher();
        this.ignoreFlight = new IgnoreFlightStage(matcher);
        this.recipient = new RecipientStage(matcher);
        this.pipeline = new DeliveryItemPipeline(matcher);
    }

    public RuleBasedDeliveryDetails computeForFlight(UUID flightId) {
        FlightId id = FlightId.of(flightId);
        Flight flight = flights.findByIdWithCrew(id)
                .orElseThrow(() -> new FlightNotFoundException(id));

        RuleBasedDeliveryDetails accumulator =
                RuleBasedDeliveryDetails.forClub(tenantResolver.resolveCurrentTenantIdentifier());
        LoadedFilters filters = filterLoader.load();

        MatchableFlight matchable = flightResolver.resolve(flight);

        ignoreFlight.run(accumulator, matchable, filters.doNotInvoice());
        if (accumulator.isDoNotInvoiceFlight()) {
            return accumulator;
        }

        recipient.run(accumulator, matchable, filters.recipient());

        pipeline.run(accumulator,
                matchable,
                filters.lineFilters(),
                flightResolver.flightDurationZeroBasedSeconds(flight),
                flightResolver.engineRunningTimeSeconds(flight),
                resolveTow(flight),
                creditsForBilledPerson(accumulator.recipient(), flight.getStartDateTime()));

        return accumulator;
    }

    private List<PersonFlightTimeCredit> creditsForBilledPerson(@Nullable Recipient billed,
                                                                @Nullable Instant flightStart) {
        if (billed == null || billed.personId() == null) {
            return List.of();
        }
        Instant validityFloor = flightStart == null ? Instant.EPOCH : flightStart;
        return credits.findActiveForPersonInCurrentTenant(billed.personId()).stream()
                .filter(credit -> !credit.getValidUntil().isBefore(validityFloor))
                .toList();
    }

    private @Nullable TowInput resolveTow(Flight flight) {
        UUID towFlightId = flight.getTowFlightId();
        if (towFlightId == null) {
            return null;
        }
        return flights.findByIdWithCrew(FlightId.of(towFlightId))
                .map(tow -> new TowInput(
                        flightResolver.resolve(tow),
                        flightResolver.flightDurationZeroBasedSeconds(tow),
                        flightResolver.engineRunningTimeSeconds(tow)))
                .orElse(null);
    }
}

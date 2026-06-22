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

/**
 * The rules-engine orchestrator — the application-layer port of the legacy
 * {@code DeliveryService.CreateDeliveryDetailsForFlight}. For one tenant-scoped
 * flight it loads the club's active {@code AccountingRuleFilter}s ({@code ORDER BY
 * sort_indicator, id}), resolves the flight + crew + filters into the engine's
 * pure-data inputs, runs the stages in the legacy CODE order, and returns the
 * computed {@link RuleBasedDeliveryDetails} — IN MEMORY, NO persistence (J-9 is
 * the dry-run harness; the persisted {@code Delivery} is J-10).
 *
 * <p>Stage order (legacy code order, NOT filter-type-id order): IgnoreFlight(5)
 * short-circuit → Recipient(10) first-match-wins → the line-item
 * {@link DeliveryItemPipeline} (NoLandingTax → FlightTime loop → EngineTime loop
 * → InstructorFee → tow-recurse → AdditionalFuelFee → StartTax → LandingTax
 * (+OnStartLocation) → VsfFee (+OnStartLocation)). After IgnoreFlight, a
 * do-not-invoice match returns the empty accumulator (zero items).
 *
 * <p>The billed person's {@code PersonFlightTimeCredit}s are loaded read-only and
 * threaded into the FlightTime stage's credit branch (discount + over-credit
 * split). This is the no-persist dry-run contract ({@code DeliveryService.cs:405-408}
 * AsNoTracking): the balance is read but never mutated and no transaction is
 * written — only a real persisted run records the new transaction + flips
 * {@code IsCurrent} (out of this seam's scope).
 *
 * <p>The DeliveryDetails stage (legacy {@code DeliveryDetailsRulesEngine} — the
 * {@code deliveryInformation} / {@code additionalInformation} texts) is NOT run
 * here; it depends on crew display names + the recipient's
 * {@code isChargedToClubInternal} that the current MatchableFlight / RecipientStage
 * don't carry, which would burst this seam (see the journey T-12 note).
 */
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

    /**
     * Computes the rule-based delivery details for the given flight under the
     * caller's tenant. A cross-tenant / missing flight id is invisible under the
     * {@code @TenantId} scope → {@link FlightNotFoundException} (404).
     */
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

    // The credit branch applies the BILLED person's prepaid credits, so the billed
    // person is the recipient the RecipientStage just resolved; a recipient with no
    // backing person (an account-recipient filter) carries no credits. Legacy loads
    // its dry-run credits read-only (AsNoTracking, DeliveryService.cs:405-408) and
    // filters to ValidUntil >= flight start; the load mutates nothing.
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

    // The tow flight rolls its own resolved MatchableFlight + seed durations into
    // the SAME accumulator via the pipeline's one-level recursion. The tow shares
    // the glider's operating club (Flight.linkTow), so the tenant-scoped load
    // resolves it; a missing tow leaves the recursion off (null).
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

package ch.alpenflight.server.testsupport;

import ch.alpenflight.accounting.domain.AccountingRuleFilter;
import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryCreationTest;
import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.emailtemplates.domain.EmailTemplate;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightReportRow;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.persons.domain.PersonClub;
import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayAssignmentType;
import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class TenantScopedRowBuilders {

    private TenantScopedRowBuilders() {}

    public static final String SWEEP_PREFIX = "IT_SWP_";

    @SuppressWarnings("unchecked")
    public static <E> Function<SweepFixtureContext, E> builderFor(Class<E> entityClass) {
        return (Function<SweepFixtureContext, E>) BUILDERS.get(entityClass);
    }

    public static java.util.Set<Class<?>> registered() {
        return BUILDERS.keySet();
    }

    private static final Map<Class<?>, Function<SweepFixtureContext, ?>> BUILDERS = Map.ofEntries(
            Map.entry(MemberState.class, ctx -> new MemberState(uniqueName("MS"))),
            Map.entry(Location.class, LocationSweepFactory::build),
            Map.entry(Flight.class, FlightSweepFactory::build),
            Map.entry(FlightReportRow.class, FlightReportRowSweepFactory::build),
            Map.entry(FlightType.class, FlightTypeSweepFactory::build),
            Map.entry(Article.class, ArticleSweepFactory::build),
            Map.entry(PersonClub.class, PersonClubSweepFactory::build),
            Map.entry(AircraftReservationType.class, AircraftReservationTypeSweepFactory::build),
            Map.entry(AircraftReservation.class, AircraftReservationSweepFactory::build),
            Map.entry(PlanningDayAssignmentType.class, PlanningDayAssignmentTypeSweepFactory::build),
            Map.entry(PlanningDay.class, PlanningDaySweepFactory::build),
            Map.entry(MutationAuditEvent.class, ctx -> MutationAuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .targetEntityType("LeakageSweep")
                    .occurredAt(Instant.now())
                    .build()),
            Map.entry(AccountingRuleFilter.class, AccountingRuleFilterSweepFactory::build),
            Map.entry(DeliveryCreationTest.class, DeliveryCreationTestSweepFactory::build),
            Map.entry(Delivery.class, DeliverySweepFactory::build),
            Map.entry(EmailTemplate.class, EmailTemplateSweepFactory::build),
            Map.entry(JoinRequest.class, JoinRequestSweepFactory::build),
            Map.entry(DiscoveryFlightDay.class, ctx ->
                    DiscoveryFlightDay.schedule(uniqueFutureDate(), LocalDate.EPOCH))
    );

    private static final LocalDate SWEEP_DATE_BASE = LocalDate.of(2999, 1, 1);
    private static final AtomicInteger SWEEP_DATE_OFFSET = new AtomicInteger();

    private static LocalDate uniqueFutureDate() {
        return SWEEP_DATE_BASE.plusDays(SWEEP_DATE_OFFSET.getAndIncrement());
    }

    private static String uniqueName(String label) {
        return SWEEP_PREFIX + label + "_" + Long.toString(System.nanoTime(), 36);
    }
}

package ch.alpenflight.server.testsupport;

import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightReportRow;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.persons.domain.PersonClub;
import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayAssignmentType;
import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationType;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

/**
 * Per-entity row-builder registry consumed by the S-024 leakage sweep. One
 * entry per {@code @TenantId}-bearing aggregate root. Builders construct
 * <strong>transient</strong> instances (no DB writes); the test persists via
 * the entity's Spring Data repository so each insert opens its own
 * transaction and the resolver is consulted fresh per save.
 *
 * <p>The tenant column is populated reflectively by Hibernate from the
 * resolver — builders MUST NOT set it.
 *
 * <p>New {@code @TenantId} entity in a future story = add a builder here in
 * the same PR; the floor assertion at sweep boot catches the mismatch if
 * forgotten.
 */
public final class TenantScopedRowBuilders {

    private TenantScopedRowBuilders() {}

    /** Natural-key prefix; cleanup queries scope deletes to {@code club_id} so the prefix is purely cosmetic. */
    public static final String SWEEP_PREFIX = "IT_SWP_";

    /** Returns the row builder for {@code entityClass}, or null if none registered. */
    @SuppressWarnings("unchecked")
    public static <E> Function<SweepFixtureContext, E> builderFor(Class<E> entityClass) {
        return (Function<SweepFixtureContext, E>) BUILDERS.get(entityClass);
    }

    /** All registered entity classes (test-side roster). */
    public static java.util.Set<Class<?>> registered() {
        return BUILDERS.keySet();
    }

    private static final Map<Class<?>, Function<SweepFixtureContext, ?>> BUILDERS = Map.ofEntries(
            Map.entry(MemberState.class, ctx -> new MemberState(uniqueName("MS"))),
            Map.entry(Location.class, LocationSweepFactory::build),
            Map.entry(Flight.class, FlightSweepFactory::build),
            // J-7 RM-1 flight-report read-model row. Its crew child
            // (FlightReportCrewEntry) is aggregate-internal WITHOUT @TenantId
            // (FlightCrew pattern) — deliberately NOT a sweep participant.
            Map.entry(FlightReportRow.class, FlightReportRowSweepFactory::build),
            Map.entry(FlightType.class, FlightTypeSweepFactory::build),
            Map.entry(Article.class, ArticleSweepFactory::build),
            // PersonClub is aggregate-internal under the cross-tenant Person
            // root; CascadeType.PERSIST on PersonClub.person makes
            // `save(personClub)` cascade-insert the parent Person at flush.
            Map.entry(PersonClub.class, PersonClubSweepFactory::build),
            // J-5 reservation aggregates. AircraftReservationType is a plain
            // tenant-scoped lookup; AircraftReservation seeds its five non-tenant
            // FKs (aircraft/person/location/type) so only operating_club_id fails
            // fail-closed under NO_TENANT (see AircraftReservationSweepFactory).
            Map.entry(AircraftReservationType.class, AircraftReservationTypeSweepFactory::build),
            Map.entry(AircraftReservation.class, AircraftReservationSweepFactory::build),
            // J-6 planning aggregates. PlanningDayAssignmentType is a plain
            // tenant-scoped lookup; PlanningDay seeds its one non-tenant FK
            // (location) so only operating_club_id fails fail-closed under
            // NO_TENANT (see PlanningDay*SweepFactory). PlanningDayAssignment is
            // an aggregate-internal child WITHOUT @TenantId (FlightCrew/V7
            // pattern) — deliberately NOT a sweep participant.
            Map.entry(PlanningDayAssignmentType.class, PlanningDayAssignmentTypeSweepFactory::build),
            Map.entry(PlanningDay.class, PlanningDaySweepFactory::build),
            // Save bypasses the AuditTrailService / listener so the sweep
            // exercises Hibernate's @TenantId resolver directly — same
            // discriminator-filter contract as the other tenant-scoped rows.
            Map.entry(MutationAuditEvent.class, ctx -> MutationAuditEvent.builder()
                    .action(AuditAction.CREATE)
                    .targetEntityType("LeakageSweep")
                    .occurredAt(Instant.now())
                    .build())
    );

    private static String uniqueName(String label) {
        return SWEEP_PREFIX + label + "_" + Long.toString(System.nanoTime(), 36);
    }
}

package ch.alpenflight.migration.bundle;

import ch.alpenflight.migration.bundle.accounting.AccountingRuleFilterMapper;
import ch.alpenflight.migration.bundle.accounting.AircraftReservationMapper;
import ch.alpenflight.migration.bundle.accounting.AircraftReservationTypeMapper;
import ch.alpenflight.migration.bundle.accounting.ArticleMapper;
import ch.alpenflight.migration.bundle.accounting.DeliveryItemMapper;
import ch.alpenflight.migration.bundle.accounting.DeliveryMapper;
import ch.alpenflight.migration.bundle.accounting.PersonFlightTimeCreditMapper;
import ch.alpenflight.migration.bundle.accounting.PersonFlightTimeCreditTransactionMapper;
import ch.alpenflight.migration.bundle.accounting.PlanningDayAssignmentMapper;
import ch.alpenflight.migration.bundle.accounting.PlanningDayAssignmentTypeMapper;
import ch.alpenflight.migration.bundle.accounting.PlanningDayMapper;
import ch.alpenflight.migration.bundle.flight.AircraftAircraftStateMapper;
import ch.alpenflight.migration.bundle.flight.AircraftMapper;
import ch.alpenflight.migration.bundle.flight.AircraftOperatingCounterMapper;
import ch.alpenflight.migration.bundle.flight.FlightCrewMapper;
import ch.alpenflight.migration.bundle.flight.FlightMapper;
import ch.alpenflight.migration.bundle.flight.FlightTypeMapper;
import ch.alpenflight.migration.bundle.flight.InOutboundPointMapper;
import ch.alpenflight.migration.bundle.flight.LocationMapper;
import ch.alpenflight.migration.bundle.flight.StartTypeMapper;
import ch.alpenflight.migration.bundle.identity.AuditLogMapper;
import ch.alpenflight.migration.bundle.identity.ClubMapper;
import ch.alpenflight.migration.bundle.identity.ClubStateMapper;
import ch.alpenflight.migration.bundle.identity.CountryMapper;
import ch.alpenflight.migration.bundle.identity.LanguageMapper;
import ch.alpenflight.migration.bundle.identity.MemberStateMapper;
import ch.alpenflight.migration.bundle.identity.PersonCategoryAssignmentMapper;
import ch.alpenflight.migration.bundle.identity.PersonCategoryMapper;
import ch.alpenflight.migration.bundle.identity.PersonClubMapper;
import ch.alpenflight.migration.bundle.identity.PersonMapper;
import ch.alpenflight.migration.bundle.identity.UserMapper;
import java.util.List;

/**
 * Hand-curated registry of every concrete {@link Mapper} the bundle ships
 * with. Lives in the main source set so external test contexts — notably
 * {@code MapperVsSchemaCompatibilityTest} in {@code alpenflight/server/}
 * (S-187) — can read it without scanning the classpath.
 *
 * <p>Drift guard: {@code ArchitectureTest.knownMappersListCoversEveryConcreteMapperOnTheClasspath}
 * fails loudly if a new concrete mapper is added to the classpath without
 * being registered here. Stay hand-curated — reflection-only discovery
 * makes the diff unreviewable.
 */
public final class KnownMappers {

    private static final List<Mapper> ALL = List.of(
            new CountryMapper(),
            new LanguageMapper(),
            new ClubStateMapper(),
            new ClubMapper(),
            new PersonMapper(),
            new MemberStateMapper(),
            new PersonCategoryMapper(),
            new UserMapper(),
            new PersonClubMapper(),
            new PersonCategoryAssignmentMapper(),
            new LocationMapper(),
            new InOutboundPointMapper(),
            new StartTypeMapper(),
            new FlightTypeMapper(),
            new AircraftMapper(),
            new AircraftAircraftStateMapper(),
            new AircraftOperatingCounterMapper(),
            new AircraftReservationTypeMapper(),
            new AircraftReservationMapper(),
            new PlanningDayMapper(),
            new PlanningDayAssignmentTypeMapper(),
            new PlanningDayAssignmentMapper(),
            new ArticleMapper(),
            new AccountingRuleFilterMapper(),
            new FlightMapper(),
            new FlightCrewMapper(),
            new DeliveryMapper(),
            new DeliveryItemMapper(),
            new PersonFlightTimeCreditMapper(),
            new PersonFlightTimeCreditTransactionMapper(),
            new AuditLogMapper());

    private KnownMappers() { }

    public static List<Mapper> all() {
        return ALL;
    }
}

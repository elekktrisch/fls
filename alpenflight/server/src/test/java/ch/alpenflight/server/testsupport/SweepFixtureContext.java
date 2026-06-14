package ch.alpenflight.server.testsupport;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightReportRow;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.referencedata.domain.AircraftType;
import ch.alpenflight.referencedata.domain.AircraftTypeRepository;
import ch.alpenflight.referencedata.domain.Country;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.referencedata.domain.LocationType;
import ch.alpenflight.referencedata.domain.LocationTypeRepository;
import ch.alpenflight.reservations.domain.AircraftReservationType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.web.context.WebApplicationContext;

/**
 * Fixture context handed to each S-024 leakage-sweep row builder. Seeds the
 * FK-parent rows the swept aggregates reference, entirely through the
 * <strong>production</strong> save path (domain factory → Spring Data
 * {@code JpaRepository.save}) — ADR 0027 §3 (test seeding goes through
 * production code, no raw JDBC INSERTs). The pre-J-26 {@code JdbcTemplate}
 * seam is retired: every FK parent's id is <em>minted</em> by Hibernate on
 * save and read back off the saved entity, so none needs an externally-pinned
 * id (the {@code @GeneratedValue}-overwrite wall the showcase-seed register
 * entry documents never applies here).
 *
 * <p>Tenant-scoped FK parents (Location, Flight, AircraftReservationType) are
 * saved inside {@link TenantTestContext#runAs} under a real club, so the
 * resolver fills their {@code @TenantId} column with that club even when the
 * sweep's outer context is the {@code NO_TENANT} sentinel — leaving the swept
 * aggregate's own {@code operating_club_id} as the only FK left to fail
 * fail-closed under the sentinel (the sweep's negative-path assertion).
 * Cross-tenant FK parents (Aircraft, Person) carry no {@code @TenantId} and
 * save under any context.
 *
 * <p>Reference-data ids (aircraft type / country / location type) are read
 * from their domain repositories' seed-ordered lists; the {@code NOT_PROCESSED}
 * flight process-state id comes straight off the {@link FlightProcessState}
 * enum's canonical UUID (no DB read needed).
 */
public final class SweepFixtureContext {

    private static final AtomicInteger AIRCRAFT_COUNTER = new AtomicInteger(0);

    /**
     * Pinned V4 seed UUID for the {@code RECIPIENT} accounting-rule-filter type
     * (legacy_int_id 10). Used as the {@code filter_type_id} FK parent for the
     * AccountingRuleFilter sweep row — reference data shared by every club, so a
     * canonical id is referenced directly (no domain repo exists for filter-types
     * until J-8 T-07, and the sweep needs only a valid FK target).
     */
    private static final UUID RECIPIENT_FILTER_TYPE_ID =
            UUID.fromString("019e2e15-2c00-7650-8000-000000004650");

    private final WebApplicationContext appContext;
    private final Repositories repositories;

    public SweepFixtureContext(WebApplicationContext appContext) {
        this.appContext = appContext;
        this.repositories = new Repositories(appContext);
    }

    /**
     * Seeds a cross-tenant Aircraft under {@code managingClub} via the
     * production {@link Aircraft#register} factory + repository save; returns
     * the minted id for the child FK.
     */
    public UUID seedAircraft(UUID managingClub) {
        Aircraft aircraft = Aircraft.register(
                managingClub,
                managingClub,
                firstAircraftTypeId(),
                uniqueImmatriculation(),
                null, null, null, null, null, null, null, null, null,
                2,
                null, null, null, null, null,
                false, false, false, false,
                null, null);
        Aircraft saved = repository(Aircraft.class).save(aircraft);
        return requireId(saved.getId() == null ? null : saved.getId().value(), "Aircraft");
    }

    /** Seeds a cross-tenant pilot Person via the production factory; returns the minted id. */
    public UUID seedPerson() {
        Person person = Person.register(
                TenantScopedRowBuilders.SWEEP_PREFIX + "PILOT_" + unique(),
                "Sweep",
                null);
        Person saved = repository(Person.class).save(person);
        return requireId(saved.getId() == null ? null : saved.getId().value(), "Person");
    }

    /**
     * Seeds a tenant-scoped Location under {@code club} (the resolver fills its
     * {@code @TenantId} from {@code club}, not the outer sweep context) via the
     * production factory; returns the minted id.
     */
    public UUID seedLocation(UUID club) {
        Location location = Location.create(
                TenantScopedRowBuilders.SWEEP_PREFIX + "LOC_" + unique(),
                null,
                firstCountryId(),
                firstLocationTypeId(),
                null,
                null, null,
                null, null,
                null, null, null,
                null,
                null,
                null,
                false, false, false);
        Location saved = TenantTestContext.runAs(club, () -> repository(Location.class).save(location));
        return requireId(saved.getId() == null ? null : saved.getId().value(), "Location");
    }

    /**
     * Seeds a tenant-scoped Flight under {@code club} (a minimal glider in the
     * {@code NOT_PROCESSED} state, referencing {@code aircraftId}) via the
     * production factory; returns the persisted aggregate so callers can read
     * its minted id (e.g. {@code FlightReportRow.project}).
     *
     * <p>The production {@code FlightRepository.save} publishes {@code FlightSaved},
     * which the same-transaction {@code FlightReportProjector} (J-7 RM-1) turns
     * into an auto-projected {@code t_flight_report_row} for this flight. The
     * FlightReportRow sweep needs the {@code t_flight} parent to exist but its
     * report-row PK slot to be FREE (so the sweep's own insert is the first and
     * fails at the {@code operating_club_id} FK, not the PK). So the incidental
     * projected row is removed here — through the PRODUCTION report-row
     * repository under the flight's own tenant, no JDBC.
     */
    public Flight seedFlight(UUID club, UUID aircraftId) {
        Flight flight = Flight.createGlider(aircraftId, FlightProcessState.NOT_PROCESSED.id(), emptyOps());
        return TenantTestContext.runAs(club, () -> {
            Flight saved = repository(Flight.class).save(flight);
            UUID flightId = saved.getId();
            if (flightId != null) {
                JpaRepository<FlightReportRow, UUID> reportRows = repository(FlightReportRow.class);
                if (reportRows.existsById(flightId)) {
                    reportRows.deleteById(flightId);
                }
            }
            return saved;
        });
    }

    /**
     * Seeds a tenant-scoped AircraftReservationType under {@code club} via the
     * production factory; returns the minted id for the child FK.
     */
    public UUID seedReservationType(UUID club) {
        AircraftReservationType type = AircraftReservationType.create(
                club,
                TenantScopedRowBuilders.SWEEP_PREFIX + "RTYPE_" + unique(),
                false,
                false,
                true,
                null);
        AircraftReservationType saved =
                TenantTestContext.runAs(club, () -> repository(AircraftReservationType.class).save(type));
        return requireId(saved.getId(), "AircraftReservationType");
    }

    /** First seeded aircraft-type id (V3 reference data; ordered list). */
    public UUID firstAircraftTypeId() {
        List<AircraftType> rows =
                appContext.getBean(AircraftTypeRepository.class).findAllByOrderByLegacyIntIdAsc();
        return firstReferenceId(rows.isEmpty() ? null
                : rows.getFirst().getId() == null ? null : rows.getFirst().getId().value(),
                "t_aircraft_type");
    }

    /** First seeded country id (V3 reference data; ordered list). */
    public UUID firstCountryId() {
        List<Country> rows = appContext.getBean(CountryRepository.class).findAllOrdered();
        return firstReferenceId(rows.isEmpty() ? null
                : rows.getFirst().getId() == null ? null : rows.getFirst().getId().value(),
                "t_country");
    }

    /**
     * The pinned V4 seed id of the {@code RECIPIENT} accounting-rule-filter type
     * — the {@code filter_type_id} FK parent for the AccountingRuleFilter sweep
     * row. Reference data, not tenant-scoped, so a canonical id is returned
     * directly (no DB read; mirrors the {@code FlightProcessState} enum-id usage).
     */
    public UUID firstAccountingRuleFilterTypeId() {
        return RECIPIENT_FILTER_TYPE_ID;
    }

    /** First seeded location-type id (V3 reference data; ordered list). */
    public UUID firstLocationTypeId() {
        List<LocationType> rows =
                appContext.getBean(LocationTypeRepository.class).findAllByOrderByDescriptionAsc();
        return firstReferenceId(rows.isEmpty() ? null
                : rows.getFirst().getId() == null ? null : rows.getFirst().getId().value(),
                "t_location_type");
    }

    @SuppressWarnings("unchecked")
    private <E> JpaRepository<E, UUID> repository(Class<E> entityClass) {
        Object repo = repositories.getRepositoryFor(entityClass).orElseThrow(() ->
                new IllegalStateException(
                        "No Spring Data JpaRepository for " + entityClass.getName()));
        return (JpaRepository<E, UUID>) repo;
    }

    private static String uniqueImmatriculation() {
        String stamp = Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        if (stamp.length() > 8) {
            stamp = stamp.substring(stamp.length() - 8);
        }
        return "HB-S" + stamp + String.format(Locale.ROOT, "%02d", AIRCRAFT_COUNTER.incrementAndGet() % 100);
    }

    private static String unique() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static UUID requireId(UUID id, String label) {
        if (id == null) {
            throw new IllegalStateException(label + " save returned a null id");
        }
        return id;
    }

    private static UUID firstReferenceId(UUID id, String table) {
        if (id == null) {
            throw new IllegalStateException(
                    "No row in " + table + " — V3 seed must populate at least one reference row");
        }
        return id;
    }

    private static FlightOperationalData emptyOps() {
        return new FlightOperationalData(
                null, null, null, null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                false, false,
                null, null,
                null, null,
                null,
                null,
                null, null,
                false);
    }
}

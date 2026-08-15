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

public final class SweepFixtureContext {

    private static final AtomicInteger AIRCRAFT_COUNTER = new AtomicInteger(0);

    private static final UUID V4_SEEDED_RECIPIENT_FILTER_TYPE_ID =
            UUID.fromString("019e2e15-2c00-7650-8000-000000004650");

    private final WebApplicationContext appContext;
    private final Repositories repositories;

    public SweepFixtureContext(WebApplicationContext appContext) {
        this.appContext = appContext;
        this.repositories = new Repositories(appContext);
    }

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

    public UUID seedPerson() {
        Person person = Person.register(
                TenantScopedRowBuilders.SWEEP_PREFIX + "PILOT_" + unique(),
                "Sweep",
                null);
        Person saved = repository(Person.class).save(person);
        return requireId(saved.getId() == null ? null : saved.getId().value(), "Person");
    }

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

    public Flight seedFlight(UUID club, UUID aircraftId) {
        Flight flight = Flight.createGlider(aircraftId, FlightProcessState.NOT_PROCESSED.id(), emptyOps());
        return TenantTestContext.runAs(club, () -> {
            Flight saved = repository(Flight.class).save(flight);
            UUID flightId = saved.getId();
            if (flightId != null) {
                deleteAutoProjectedReportRowSoTheSweepsOwnInsertIsTheFirst(flightId);
            }
            return saved;
        });
    }

    private void deleteAutoProjectedReportRowSoTheSweepsOwnInsertIsTheFirst(UUID flightId) {
        JpaRepository<FlightReportRow, UUID> reportRows = repository(FlightReportRow.class);
        if (reportRows.existsById(flightId)) {
            reportRows.deleteById(flightId);
        }
    }

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

    public UUID firstAircraftTypeId() {
        List<AircraftType> rows =
                appContext.getBean(AircraftTypeRepository.class).findAllByOrderByLegacyIntIdAsc();
        return firstReferenceId(rows.isEmpty() ? null
                : rows.getFirst().getId() == null ? null : rows.getFirst().getId().value(),
                "t_aircraft_type");
    }

    public UUID firstCountryId() {
        List<Country> rows = appContext.getBean(CountryRepository.class).findAllOrdered();
        return firstReferenceId(rows.isEmpty() ? null
                : rows.getFirst().getId() == null ? null : rows.getFirst().getId().value(),
                "t_country");
    }

    // RENAME: firstAccountingRuleFilterTypeId -> seededRecipientFilterTypeId
    public UUID firstAccountingRuleFilterTypeId() {
        return V4_SEEDED_RECIPIENT_FILTER_TYPE_ID;
    }

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

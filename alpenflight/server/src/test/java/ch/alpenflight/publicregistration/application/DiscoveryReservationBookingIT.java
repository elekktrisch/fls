package ch.alpenflight.publicregistration.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.flighttypes.domain.FlightType;
import ch.alpenflight.flighttypes.domain.FlightTypeRepository;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.publicregistration.application.DiscoveryReservationOutcome.Status;
import ch.alpenflight.publicregistration.application.PublicRegistrationIntake.Accepted;
import ch.alpenflight.referencedata.domain.AircraftType;
import ch.alpenflight.referencedata.domain.AircraftTypeRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.referencedata.domain.LocationTypeRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The discovery-flight reservation, driven through the production intake
 * ({@code RegistrationService.cs:152-199}).
 *
 * <p>The load-bearing case is
 * {@link #two_candidates_on_the_same_day_each_get_their_own_overlapping_slot}:
 * the member-booking service's exclusivity probe would answer 409 for the second
 * candidate, which is exactly why
 * {@code DiscoveryReservationBooker} goes to the aggregate + repository instead.
 *
 * <p>Three aircraft that must NOT be picked are seeded alongside the one that
 * must — another club's double-seater glider, a single-seat glider, and a
 * motorised glider — so the owner / seat-count / type predicate is proved to
 * narrow rather than passing on an empty fleet. Exactly one aircraft ever
 * matches, because legacy's {@code FirstOrDefault} has no {@code ORDER BY} and a
 * second match would make "which glider" a DB-order coin flip.
 */
class DiscoveryReservationBookingIT extends PostgresIntegrationTest {

    private static final LocalDate DISCOVERY_DAY = LocalDate.of(2099, 6, 15);

    @Autowired PublicRegistrationIntake intake;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired LocationTypeRepository locationTypes;
    @Autowired LocationRepository locations;
    @Autowired AircraftRepository aircraft;
    @Autowired AircraftTypeRepository aircraftTypes;
    @Autowired FlightTypeRepository flightTypes;
    @Autowired JdbcTemplate jdbc;

    private UUID clubId;
    private UUID otherClubId;
    private String slug;
    private UUID discoveryFlightTypeId;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DRB_", "IT_DRB_");
        fixture.seed();
        clubId = fixture.clubA();
        otherClubId = fixture.clubB();

        Club club = clubs.findActiveById(clubId).orElseThrow();
        club.enablePublicRegistration();
        clubs.save(club);
        slug = Objects.requireNonNull(club.getSlug(), "fixture club has no slug");

        discoveryFlightTypeId = TenantTestContext.runAs(clubId, () -> {
            FlightType type = flightTypes.save(FlightType.register(
                    "IT_DRB_Schnupperflug", "DRB", false, false, false, true, false,
                    true, false, false, false, false, true, 2));
            return Objects.requireNonNull(type.getId()).value();
        });
        setDiscoveryFlightType(discoveryFlightTypeId);

        // Decoys: every one of them fails exactly one clause of the
        // owner-club / two-seats / pure-glider predicate.
        registerAircraft(otherClubId, otherClubId, "GLIDER", 2, "HB-DRB1");
        registerAircraft(clubId, clubId, "GLIDER", 1, "HB-DRB2");
        registerAircraft(clubId, clubId, "GLIDER_WITH_MOTOR", 2, "HB-DRB3");
        // Managed here but owned elsewhere — legacy asks who OWNS the airframe.
        registerAircraft(clubId, otherClubId, "GLIDER", 2, "HB-DRB4");
    }

    @Test
    void a_discovery_registration_books_the_club_double_seater_all_day_for_the_candidate() {
        UUID homebaseId = giveClubAHomebase();
        UUID gliderId = giveClubADoubleSeaterGlider();

        Accepted accepted = intake.acceptDiscovery(
                slug, "198.51.100.21", registrant("Rosa"), DISCOVERY_DAY);

        DiscoveryReservationOutcome outcome = requireOutcome(accepted);
        assertThat(outcome.status()).isEqualTo(Status.BOOKED);
        assertThat(outcome.reservationId()).isNotNull();

        Map<String, Object> row = onlyReservation();
        assertThat(row.get("id")).isEqualTo(outcome.reservationId());
        assertThat(row.get("operating_club_id")).isEqualTo(clubId);
        assertThat(row.get("aircraft_id")).isEqualTo(gliderId);
        assertThat(row.get("pilot_person_id"))
                .isEqualTo(accepted.registered().registrantPersonId());
        assertThat(row.get("location_id")).isEqualTo(homebaseId);
        assertThat(row.get("flight_type_id")).isEqualTo(discoveryFlightTypeId);
        assertThat(row.get("reservation_type_id")).isNull();
        assertThat(row.get("second_crew_person_id")).isNull();
        assertThat(row.get("is_all_day")).isEqualTo(true);
        assertThat(row.get("info")).isEqualTo("Schnupperflug-Kandidat");
        assertThat(instantAt(row, "reservation_start")).isEqualTo(dayStart());
        assertThat(instantAt(row, "reservation_end"))
                .as("all-day is the half-open [date 00:00, +1 day) span, not legacy's "
                        + "AddTicks(-1) artifact")
                .isEqualTo(dayStart().plus(java.time.Duration.ofDays(1)));
    }

    @Test
    void two_candidates_on_the_same_day_each_get_their_own_overlapping_slot() {
        giveClubAHomebase();
        UUID gliderId = giveClubADoubleSeaterGlider();

        Accepted first = intake.acceptDiscovery(
                slug, "198.51.100.22", registrant("Rosa"), DISCOVERY_DAY);
        Accepted second = intake.acceptDiscovery(
                slug, "198.51.100.23", registrant("Sepp"), DISCOVERY_DAY);

        assertThat(requireOutcome(first).status()).isEqualTo(Status.BOOKED);
        assertThat(requireOutcome(second).status()).isEqualTo(Status.BOOKED);
        assertThat(requireOutcome(first).reservationId())
                .isNotEqualTo(requireOutcome(second).reservationId());

        List<Map<String, Object>> rows = reservationRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("aircraft_id")).isEqualTo(gliderId);
            assertThat(instantAt(row, "reservation_start")).isEqualTo(dayStart());
        });
        assertThat(rows.stream().map(row -> row.get("pilot_person_id")))
                .containsExactlyInAnyOrder(first.registered().registrantPersonId(),
                        second.registered().registrantPersonId());
    }

    @Test
    void a_club_without_a_double_seater_still_registers_the_candidate() {
        giveClubAHomebase();

        Accepted accepted = intake.acceptDiscovery(
                slug, "198.51.100.24", registrant("Rosa"), DISCOVERY_DAY);

        assertRegisteredWithoutReservation(accepted, Status.SKIPPED_NO_DOUBLE_SEATER);
    }

    @Test
    void a_club_without_a_homebase_still_registers_the_candidate() {
        giveClubADoubleSeaterGlider();

        Accepted accepted = intake.acceptDiscovery(
                slug, "198.51.100.25", registrant("Rosa"), DISCOVERY_DAY);

        assertRegisteredWithoutReservation(accepted, Status.SKIPPED_NO_HOMEBASE);
    }

    @Test
    void a_club_missing_both_prerequisites_reports_the_combined_reason() {
        Accepted accepted = intake.acceptDiscovery(
                slug, "198.51.100.26", registrant("Rosa"), DISCOVERY_DAY);

        assertRegisteredWithoutReservation(
                accepted, Status.SKIPPED_NO_DOUBLE_SEATER_AND_NO_HOMEBASE);
    }

    @Test
    void an_unset_discovery_flight_type_does_not_cost_the_club_the_booking() {
        giveClubAHomebase();
        giveClubADoubleSeaterGlider();
        setDiscoveryFlightType(null);

        Accepted accepted = intake.acceptDiscovery(
                slug, "198.51.100.27", registrant("Rosa"), DISCOVERY_DAY);

        assertThat(requireOutcome(accepted).status()).isEqualTo(Status.BOOKED);
        assertThat(onlyReservation().get("flight_type_id")).isNull();
    }

    /**
     * The scenic flow's one behavioural difference from discovery
     * ({@code RegistrationService.cs:269-411} books nothing). The club is fully
     * bookable and the discovery submission that follows proves it, so the empty
     * reservation count is the scenic flow's doing rather than an inert fixture.
     */
    @Test
    void a_scenic_registration_books_nothing_in_a_club_discovery_can_book_in() {
        giveClubAHomebase();
        giveClubADoubleSeaterGlider();

        Accepted scenic = intake.acceptScenic(slug, "198.51.100.28", registrant("Silvia"));

        assertThat(scenic.registered().registrantPersonId()).isNotNull();
        assertThat(scenic.reservation()).isNull();
        assertThat(reservationRows()).isEmpty();

        Accepted discovery = intake.acceptDiscovery(
                slug, "198.51.100.29", registrant("Rosa"), DISCOVERY_DAY);

        assertThat(requireOutcome(discovery).status()).isEqualTo(Status.BOOKED);
        assertThat(onlyReservation().get("pilot_person_id"))
                .isEqualTo(discovery.registered().registrantPersonId());
    }

    private void assertRegisteredWithoutReservation(Accepted accepted, Status expected) {
        DiscoveryReservationOutcome outcome = requireOutcome(accepted);
        assertThat(outcome.status()).isEqualTo(expected);
        assertThat(outcome.reservationId()).isNull();
        assertThat(outcome.messageKey()).isEqualTo(expected.messageKey());
        assertThat(accepted.registered().registrantPersonId())
                .as("a skipped reservation must not cost the club the registration")
                .isNotNull();
        assertThat(reservationRows()).isEmpty();
    }

    private static DiscoveryReservationOutcome requireOutcome(Accepted accepted) {
        return Objects.requireNonNull(accepted.reservation(),
                "discovery registration carries no reservation outcome");
    }

    private UUID giveClubAHomebase() {
        UUID homebaseId = TenantTestContext.runAs(clubId, () -> {
            Location home = locations.save(Location.create(
                    "IT_DRB_Homebase", null, firstCountryId(), firstLocationTypeId(),
                    null, null, null, null, null, null, null, null, null, null, null,
                    false, false, false));
            return Objects.requireNonNull(home.getId()).value();
        });
        // No aggregate write path for the homebase FK yet (Club.java:413) — the
        // column is populated by migration only, so the fixture writes it directly.
        jdbc.update("UPDATE t_club SET homebase_id = ?::uuid WHERE id = ?::uuid",
                homebaseId.toString(), clubId.toString());
        return homebaseId;
    }

    private UUID giveClubADoubleSeaterGlider() {
        return registerAircraft(clubId, clubId, "GLIDER", 2, "HB-DRB9");
    }

    private UUID registerAircraft(UUID managingClubId, UUID ownerClubId,
            String typeCode, int seats, String immatriculation) {
        Aircraft registered = aircraft.save(Aircraft.register(
                managingClubId, ownerClubId, aircraftTypeId(typeCode), immatriculation,
                null, null, null, null, null, null, null, null, null, seats,
                null, null, null, null, null,
                false, false, false, false, null, null));
        return Objects.requireNonNull(registered.getId()).value();
    }

    private void setDiscoveryFlightType(UUID flightTypeId) {
        Club club = clubs.findActiveById(clubId).orElseThrow();
        club.setDiscoveryFlightType(flightTypeId);
        clubs.save(club);
    }

    private UUID aircraftTypeId(String code) {
        return aircraftTypes.findAllByOrderByLegacyIntIdAsc().stream()
                .filter(type -> code.equals(type.getCode()))
                .map(AircraftType::getId)
                .filter(Objects::nonNull)
                .map(id -> id.value())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No t_aircraft_type row " + code));
    }

    private UUID firstCountryId() {
        return Objects.requireNonNull(countries.findAllOrdered().getFirst().getId()).value();
    }

    private UUID firstLocationTypeId() {
        return Objects.requireNonNull(
                locationTypes.findAllByOrderByDescriptionAsc().getFirst().getId()).value();
    }

    private static PublicRegistrantDetails registrant(String firstname) {
        return new PublicRegistrantDetails(
                firstname, "Renggli", "Flugplatzstrasse 7", "6060", "Sarnen", null,
                null, null, "079 555 66 77",
                firstname.toLowerCase(Locale.ROOT) + ".renggli@example.ch",
                true, null);
    }

    private Instant dayStart() {
        return DISCOVERY_DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Map<String, Object> onlyReservation() {
        List<Map<String, Object>> rows = reservationRows();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Map<String, Object>> reservationRows() {
        return jdbc.queryForList(
                "SELECT * FROM t_aircraft_reservation "
                        + "WHERE operating_club_id = ?::uuid AND deleted_on IS NULL",
                clubId.toString());
    }

    private static Instant instantAt(Map<String, Object> row, String column) {
        return ((java.sql.Timestamp) row.get(column)).toInstant();
    }
}

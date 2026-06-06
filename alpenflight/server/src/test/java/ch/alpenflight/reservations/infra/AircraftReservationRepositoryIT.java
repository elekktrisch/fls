package ch.alpenflight.reservations.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationRepository;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.ListRow;
import ch.alpenflight.reservations.domain.AircraftReservationRepository.Range;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level proof of the J-5 conflict GiST probe + soft-delete
 * exclusion (T-04), driven through the real {@code @TenantId} discriminator via
 * {@link Tenants#runAs}. The aggregate's pure {@code conflictsWith} predicate
 * has its own domain unit test; this IT proves the native {@code &&}
 * range-overlap query against the V4 generated {@code reservation_range} column
 * agrees with it and respects soft-delete + tenant scoping.
 *
 * <p>Each test owns its data: {@code @BeforeEach} deletes the test clubs'
 * reservation rows first (they FK to aircraft with {@code ON DELETE RESTRICT},
 * so they must go before {@link TwoClubFixture#seed()} wipes the seed aircraft),
 * then re-seeds two clubs + an aircraft per club + a shared pilot + a location.
 */
class AircraftReservationRepositoryIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c5-2c00-7001-8000-0000000000d1");
    private static final UUID CLUB_B = UUID.fromString("019e30c5-2c00-7001-8000-0000000000d2");
    private static final String NAME_PREFIX = "IT_ARV_";
    private static final String KEY_PREFIX = "IT_AV_";

    // Booking windows: B follows A adjacently ([)), C overlaps A.
    private static final Instant A_START = Instant.parse("2026-07-01T08:00:00Z");
    private static final Instant A_END = Instant.parse("2026-07-01T10:00:00Z");
    private static final Instant ADJACENT_START = A_END; // == A_END → no conflict
    private static final Instant ADJACENT_END = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant OVERLAP_START = Instant.parse("2026-07-01T09:00:00Z");
    private static final Instant OVERLAP_END = Instant.parse("2026-07-01T11:00:00Z");

    @Autowired private AircraftReservationRepository reservations;
    @Autowired private JdbcTemplate jdbc;

    private final Clock clock = Clock.systemUTC();

    private UUID aircraftA;
    private UUID aircraftB;
    private UUID pilotId;
    private UUID locationId;

    @BeforeEach
    void seed() {
        // Reservations FK to aircraft (RESTRICT); clear them before the fixture
        // wipes the seed aircraft under these clubs.
        jdbc.update("DELETE FROM t_aircraft_reservation WHERE operating_club_id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, NAME_PREFIX, KEY_PREFIX).seed();

        aircraftA = seedAircraft(CLUB_A);
        aircraftB = seedAircraft(CLUB_B);
        pilotId = seedPerson();
        locationId = seedLocation(CLUB_A);
    }

    @Test
    void overlap_conflicts_but_adjacent_and_other_aircraft_and_self_do_not() {
        AircraftReservation existing = Tenants.runAs(CLUB_A,
                () -> reservations.save(timed(aircraftA, A_START, A_END)));
        UUID existingId = existing.getId();
        assertThat(existingId).isNotNull();

        Tenants.runAs(CLUB_A, () -> {
            // Overlapping window on the SAME aircraft → conflict.
            assertThat(reservations.existsActiveConflict(
                    aircraftA, new Range(OVERLAP_START, OVERLAP_END), null))
                    .as("overlapping range on the same aircraft conflicts")
                    .isTrue();

            // Adjacent half-open range (end == next.start) → no conflict.
            assertThat(reservations.existsActiveConflict(
                    aircraftA, new Range(ADJACENT_START, ADJACENT_END), null))
                    .as("adjacent [) range (end == next.start) does not conflict")
                    .isFalse();

            // Same overlapping window on a DIFFERENT aircraft → no conflict.
            assertThat(reservations.existsActiveConflict(
                    aircraftB, new Range(OVERLAP_START, OVERLAP_END), null))
                    .as("overlap on a different aircraft does not conflict")
                    .isFalse();

            // Self-exclusion: the row being edited does not conflict with itself.
            assertThat(reservations.existsActiveConflict(
                    aircraftA, new Range(A_START, A_END), existingId))
                    .as("editing the overlapping row in place is self-excluded")
                    .isFalse();
            return null;
        });
    }

    @Test
    void soft_deleted_rows_are_excluded_from_probe_and_list() {
        AircraftReservation booked = Tenants.runAs(CLUB_A,
                () -> reservations.save(timed(aircraftA, A_START, A_END)));

        Tenants.runAs(CLUB_A, () -> {
            assertThat(reservations.existsActiveConflict(
                    aircraftA, new Range(OVERLAP_START, OVERLAP_END), null))
                    .as("sanity: alive row conflicts before delete")
                    .isTrue();

            booked.softDelete(null, clock);
            reservations.save(booked);
            reservations.flush();

            assertThat(reservations.existsActiveConflict(
                    aircraftA, new Range(OVERLAP_START, OVERLAP_END), null))
                    .as("soft-deleted row no longer conflicts — the slot is free")
                    .isFalse();

            assertThat(reservations.findAllActiveListRows())
                    .as("soft-deleted row excluded from the active list")
                    .extracting(ListRow::id)
                    .doesNotContain(booked.getId());
            return null;
        });
    }

    @Test
    void list_rows_and_range_window_round_trip_under_tenant() {
        AircraftReservation saved = Tenants.runAs(CLUB_A,
                () -> reservations.save(timed(aircraftA, A_START, A_END)));

        Tenants.runAs(CLUB_A, () -> {
            List<ListRow> all = reservations.findAllActiveListRows();
            assertThat(all).extracting(ListRow::id).contains(saved.getId());

            // Range view: a window overlapping the booking returns it; an
            // adjacent-only window does not.
            assertThat(reservations.findActiveListRowsByAircraftInRange(
                    aircraftA, new Range(OVERLAP_START, OVERLAP_END)))
                    .extracting(ListRow::id)
                    .contains(saved.getId());
            assertThat(reservations.findActiveListRowsByAircraftInRange(
                    aircraftA, new Range(ADJACENT_START, ADJACENT_END)))
                    .extracting(ListRow::id)
                    .doesNotContain(saved.getId());
            return null;
        });
    }

    private AircraftReservation timed(UUID aircraftId, Instant start, Instant end) {
        return AircraftReservation.create(
                CLUB_A, aircraftId, pilotId, locationId,
                null, null, start, end, false, null, "IT booking");
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID id = UUID.randomUUID();
        UUID aircraftTypeId = jdbc.queryForObject(
                "SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                                      immatriculation, is_towing_or_winch_required,
                                      is_towing_start_allowed, is_winch_start_allowed,
                                      is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, false, false, false, false, false, 2)
                """,
                id.toString(), managingClubId.toString(), managingClubId.toString(),
                aircraftTypeId.toString(), uniqueImmat());
        return id;
    }

    private UUID seedPerson() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                id.toString(), "Res", "Pilot");
        return id;
    }

    private UUID seedLocation(UUID clubId) {
        UUID id = UUID.randomUUID();
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID locationTypeId = jdbc.queryForObject(
                "SELECT id FROM t_location_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id,
                        is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, false, false, false)
                """,
                id.toString(), clubId.toString(), NAME_PREFIX + "Home",
                countryId.toString(), locationTypeId.toString());
        return id;
    }

    private static String uniqueImmat() {
        String suffix = Long.toString(System.nanoTime(), 36);
        return ("HB-R" + suffix).substring(0, Math.min(15, 4 + suffix.length()));
    }
}

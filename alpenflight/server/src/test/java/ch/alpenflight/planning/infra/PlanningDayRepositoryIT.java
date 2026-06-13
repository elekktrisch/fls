package ch.alpenflight.planning.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayConflictException;
import ch.alpenflight.planning.domain.PlanningDayRepository;
import ch.alpenflight.planning.domain.PlanningDayRepository.ListRow;
import ch.alpenflight.planning.domain.PlanningDayRepository.Page;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level proof of the J-6 planning-day repository (T-03), driven
 * through the real {@code @TenantId} discriminator via {@link Tenants#runAs}:
 *
 * <ul>
 *   <li>paged future-days returns tenant-scoped rows in {@code planning_date}
 *       order, past days + the other club's days excluded;</li>
 *   <li>a duplicate {@code (club, date, location)} save surfaces the catchable
 *       {@link PlanningDayConflictException} (not a raw constraint-violation
 *       500), and a soft-deleted day frees the tuple;</li>
 *   <li>the per-day {@code countReservationsForDay} joins
 *       {@code t_aircraft_reservation} by club + {@code date(reservation_start)}
 *       + location;</li>
 *   <li>a cross-tenant {@code findActiveById} returns empty.</li>
 * </ul>
 *
 * <p>Each test owns its data: {@code @BeforeEach} clears the test clubs'
 * planning + reservation rows (RESTRICT FKs to Location/Aircraft), then re-seeds
 * two clubs + a location per club.
 */
class PlanningDayRepositoryIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_PLN_";
    private static final String KEY_PREFIX = "IT_PL_";

    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneOffset.UTC);
    private static final LocalDate DAY_1 = TODAY.plusDays(1);
    private static final LocalDate DAY_2 = TODAY.plusDays(2);
    private static final LocalDate DAY_3 = TODAY.plusDays(3);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired private PlanningDayRepository planningDays;
    @Autowired private AircraftReservationRepository reservations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;
    private UUID locationA;
    private UUID locationB;

    @BeforeEach
    void seed() {
        // The fixture mints the two clubs and clears all their tenant-scoped
        // children (planning days + reservations FK to location/aircraft with
        // RESTRICT) in one global child→parent pass before re-minting.
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        locationA = seedLocation(clubA);
        locationB = seedLocation(clubB);
    }

    @Test
    void future_page_returns_tenant_scoped_rows_in_date_order_excluding_past_and_other_club() {
        Tenants.runAs(clubA, () -> {
            // Insert out of order; future page must sort by planning_date asc.
            planningDays.save(PlanningDay.create(clubA, DAY_3, locationA, "third"));
            planningDays.save(PlanningDay.create(clubA, DAY_1, locationA, "first"));
            planningDays.save(PlanningDay.create(clubA, DAY_2, locationA, "second"));
            planningDays.save(PlanningDay.create(clubA, YESTERDAY, locationA, "past"));
            return null;
        });
        // Other club's future day — must NOT appear in A's page.
        Tenants.runAs(clubB,
                () -> planningDays.save(PlanningDay.create(clubB, DAY_1, locationB, "club-b")));

        Tenants.runAs(clubA, () -> {
            Page page = planningDays.findFuturePage(TODAY, 0, 100);
            assertThat(page.pageSize()).isEqualTo(100);
            assertThat(page.pageStart()).isZero();
            assertThat(page.totalRows())
                    .as("4 inserted, 1 in the past → 3 future days")
                    .isEqualTo(3);
            assertThat(page.items())
                    .extracting(ListRow::planningDate)
                    .as("sorted by planning_date asc, past + other-club excluded")
                    .containsExactly(DAY_1, DAY_2, DAY_3);

            assertThat(planningDays.findFutureListRows(TODAY))
                    .extracting(ListRow::planningDate)
                    .containsExactly(DAY_1, DAY_2, DAY_3);
            return null;
        });
    }

    @Test
    void duplicate_club_date_location_save_surfaces_catchable_conflict() {
        UUID firstId = Tenants.runAs(clubA, () -> {
            PlanningDay saved = planningDays.save(PlanningDay.create(clubA, DAY_1, locationA, "dup"));
            return saved.getId();
        });
        assertThat(firstId).isNotNull();

        Tenants.runAs(clubA, () -> {
            assertThatThrownBy(() ->
                    planningDays.save(PlanningDay.create(clubA, DAY_1, locationA, "dup-again")))
                    .as("duplicate (club,date,location) → catchable conflict, not a raw 500")
                    .isInstanceOf(PlanningDayConflictException.class);
            return null;
        });

        // Soft-deleting the first frees the (club,date,location) tuple — the
        // ux_pln_club_date_loc index is partial (WHERE deleted_on IS NULL, V4).
        Tenants.runAs(clubA, () -> {
            PlanningDay existing = planningDays.findActiveById(firstId).orElseThrow();
            existing.softDelete(null, java.time.Clock.systemUTC());
            planningDays.save(existing);
            planningDays.flush();
            // Same tuple now inserts cleanly.
            PlanningDay reinserted = planningDays.save(
                    PlanningDay.create(clubA, DAY_1, locationA, "reinserted"));
            assertThat(reinserted.getId()).isNotNull();
            return null;
        });
    }

    @Test
    void per_day_reservation_count_joins_by_club_date_and_location() {
        UUID aircraftA = seedAircraft(clubA);
        UUID pilot = seedPerson();
        // Two reservations on DAY_1 at locationA, one on DAY_1 at locationB,
        // one on DAY_2 at locationA — all under clubA.
        seedReservation(clubA, aircraftA, pilot, locationA, DAY_1, 8);
        seedReservation(clubA, aircraftA, pilot, locationA, DAY_1, 12);
        seedReservation(clubA, aircraftA, pilot, locationB, DAY_1, 8);
        seedReservation(clubA, aircraftA, pilot, locationA, DAY_2, 8);
        // Other club's reservation on DAY_1 at locationA — must not be counted.
        UUID aircraftB = seedAircraft(clubB);
        seedReservation(clubB, aircraftB, pilot, locationB, DAY_1, 8);

        Tenants.runAs(clubA, () -> {
            assertThat(planningDays.countReservationsForDay(DAY_1, locationA))
                    .as("two clubA reservations on DAY_1 at locationA")
                    .isEqualTo(2);
            assertThat(planningDays.countReservationsForDay(DAY_2, locationA))
                    .isEqualTo(1);
            assertThat(planningDays.countReservationsForDay(DAY_3, locationA))
                    .isZero();
            return null;
        });
        // clubB sees only its own reservation, not clubA's.
        Tenants.runAs(clubB, () -> {
            assertThat(planningDays.countReservationsForDay(DAY_1, locationA))
                    .as("clubB has no reservation at locationA")
                    .isZero();
            return null;
        });
    }

    /**
     * J-26 T-16b — midnight-boundary proof of the UTC day window backing
     * {@code countReservationsForDay}. The retired native probe truncated with
     * {@code date(reservation_start)}, which applies the Postgres SESSION
     * TimeZone; the replacement {@code countActiveOnDayAtLocation} compares the
     * {@code timestamptz} start against the UTC instant window {@code [date 00:00
     * UTC, date+1 00:00 UTC)}. Because both operands are instants (no
     * tz-dependent {@code ::date} cast), the bucketing is independent of the
     * runner's JVM tz and the DB session tz — and {@code hibernate.jdbc.time_zone}
     * is NOT pinned to UTC in any profile, so this case is the only thing proving
     * the boundary doesn't drift on a non-UTC CI runner.
     *
     * <p>Two reservations straddle the {@code DAY_1 → DAY_2} midnight: one at
     * {@code DAY_1 23:30:00Z} (belongs to DAY_1 in UTC) and one at {@code DAY_2
     * 00:30:00Z} (belongs to DAY_2). The DAY_1 count must include ONLY the 23:30Z
     * row, and the DAY_2 count ONLY the 00:30Z row — neither leaking across the
     * boundary. Seeded through the production save path (ADR 0027): an
     * {@code Instant} binds to {@code timestamptz} as a true instant, so the seed
     * itself carries no JVM-default-tz ambiguity.
     */
    @Test
    void per_day_reservation_count_buckets_midnight_boundary_by_utc_window() {
        UUID aircraftA = seedAircraft(clubA);
        UUID pilot = seedPerson();

        // 23:30Z on DAY_1 → DAY_1 bucket; 00:30Z on DAY_2 → DAY_2 bucket.
        Instant lateOnDay1 = DAY_1.atStartOfDay(java.time.ZoneOffset.UTC)
                .plusHours(23).plusMinutes(30).toInstant();
        Instant earlyOnDay2 = DAY_2.atStartOfDay(java.time.ZoneOffset.UTC)
                .plusMinutes(30).toInstant();

        seedTimedReservation(aircraftA, pilot, locationA, lateOnDay1);
        seedTimedReservation(aircraftA, pilot, locationA, earlyOnDay2);

        Tenants.runAs(clubA, () -> {
            assertThat(planningDays.countReservationsForDay(DAY_1, locationA))
                    .as("only the 23:30Z reservation belongs to DAY_1 in UTC "
                            + "(the 00:30Z one is DAY_2, must not leak back)")
                    .isEqualTo(1);
            assertThat(planningDays.countReservationsForDay(DAY_2, locationA))
                    .as("only the 00:30Z reservation belongs to DAY_2 in UTC "
                            + "(the 23:30Z one is DAY_1, must not leak forward)")
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    void cross_tenant_find_by_id_returns_empty() {
        UUID dayId = Tenants.runAs(clubA,
                () -> planningDays.save(PlanningDay.create(clubA, DAY_1, locationA, "a")).getId());

        Tenants.runAs(clubA, () ->
                assertThat(planningDays.findActiveById(dayId))
                        .as("own tenant sees its day").isPresent());
        Tenants.runAs(clubB, () ->
                assertThat(planningDays.findActiveById(dayId))
                        .as("other tenant cannot read club A's day (→ 404)").isEmpty());
    }

    private UUID seedLocation(UUID clubId) {
        UUID id = UUID.randomUUID();
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID locationTypeId = jdbc.queryForObject("SELECT id FROM t_location_type LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id,
                        is_inbound_route_required, is_outbound_route_required, is_fast_entry_record)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, false, false, false)
                """,
                id.toString(), clubId.toString(), NAME_PREFIX + "Home_" + nano(),
                countryId.toString(), locationTypeId.toString());
        return id;
    }

    private UUID seedAircraft(UUID managingClubId) {
        UUID id = UUID.randomUUID();
        UUID aircraftTypeId = jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
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
                id.toString(), "Pln", "Pilot");
        return id;
    }

    private void seedReservation(UUID clubId, UUID aircraftId, UUID pilotId,
                                 UUID locationId, LocalDate day, int startHour) {
        Instant start = day.atStartOfDay(java.time.ZoneOffset.UTC)
                .plusHours(startHour).toInstant().truncatedTo(ChronoUnit.SECONDS);
        Instant end = start.plus(2, ChronoUnit.HOURS);
        jdbc.update("""
                INSERT INTO t_aircraft_reservation
                    (id, operating_club_id, aircraft_id, pilot_person_id, location_id,
                     reservation_start, reservation_end, is_all_day)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, false)
                """,
                UUID.randomUUID().toString(), clubId.toString(), aircraftId.toString(),
                pilotId.toString(), locationId.toString(),
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
    }

    /**
     * Seeds a 2-hour timed reservation starting at exactly {@code start} through
     * the production save path (ADR 0027) under the row's operating club, so the
     * {@code Instant} binds to {@code timestamptz} as a true instant — no
     * {@code java.sql.Timestamp}/JVM-default-tz round-trip. Used by the
     * midnight-boundary case where the stored instant must be exact.
     */
    private void seedTimedReservation(UUID aircraftId, UUID pilotId, UUID locationId,
                                      Instant start) {
        Instant end = start.plus(2, ChronoUnit.HOURS);
        Tenants.runAs(clubA, () -> reservations.save(AircraftReservation.create(
                clubA, aircraftId, pilotId, locationId, null, null,
                start, end, false, null, "IT_PLN_boundary")));
    }

    private static String nano() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String uniqueImmat() {
        String suffix = Long.toString(System.nanoTime(), 36);
        return ("HB-P" + suffix).substring(0, Math.min(15, 4 + suffix.length()));
    }
}

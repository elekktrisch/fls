package ch.alpenflight.tenancy.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

class SandboxSeederRunDateRelativeIT extends PostgresIntegrationTest {

    private static final Instant RUN_INSTANT_YEARS_AFTER_THE_AUTHORING_DATE =
            Instant.parse("2031-03-17T09:15:00Z");

    private static final LocalDate RUN_DATE =
            LocalDate.ofInstant(RUN_INSTANT_YEARS_AFTER_THE_AUTHORING_DATE, ZoneOffset.UTC);

    private static final int SEAT_UNDER_TEST = 3;
    private static final UUID SEAT_3_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-0000000de003");
    private static final List<String> SEAT_3_IMMATRICULATIONS =
            List.of("HB-3103", "HB-3203", "HB-2303", "HB-KDC");

    private static final List<Integer> EXPECTED_FLYING_DAYS_BEFORE_THE_RUN_DATE =
            List.of(2, 5, 9, 12, 19, 26);
    private static final List<Integer> EXPECTED_RESERVATION_DAYS_AFTER_THE_RUN_DATE =
            List.of(1, 2, 4, 6, 9, 12);
    private static final int EXPECTED_PLANNING_DAY_DAYS_AFTER_THE_RUN_DATE = 3;

    @TestConfiguration
    static class ClockPinnedYearsAfterTheAuthoringDate {

        @Bean
        @Primary
        Clock clockPinnedYearsAfterTheAuthoringDate() {
            return Clock.fixed(RUN_INSTANT_YEARS_AFTER_THE_AUTHORING_DATE, ZoneOffset.UTC);
        }
    }

    @Autowired
    private SandboxSeeder seeder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reclaimTheSeatBeforeEveryCase() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE tenant_club_id = ?::uuid",
                SEAT_3_CLUB.toString());
        jdbc.update("DELETE FROM t_flight_report_row WHERE operating_club_id = ?::uuid",
                SEAT_3_CLUB.toString());
        jdbc.update("DELETE FROM t_flight WHERE operating_club_id = ?::uuid",
                SEAT_3_CLUB.toString());
        jdbc.update("DELETE FROM t_aircraft_reservation WHERE operating_club_id = ?::uuid",
                SEAT_3_CLUB.toString());
        jdbc.update("DELETE FROM t_planning_day WHERE operating_club_id = ?::uuid",
                SEAT_3_CLUB.toString());
        for (String immatriculation : SEAT_3_IMMATRICULATIONS) {
            jdbc.update("DELETE FROM t_aircraft WHERE immatriculation = ?", immatriculation);
        }
        jdbc.update("DELETE FROM t_person WHERE id IN "
                        + "(SELECT person_id FROM t_person_club WHERE club_id = ?::uuid)",
                SEAT_3_CLUB.toString());
        jdbc.update("DELETE FROM t_location WHERE club_id = ?::uuid", SEAT_3_CLUB.toString());
    }

    @Test
    void everySeededOperationalDateMovesWithTheRunDateAndNeverWithTheAuthoringDate() {
        seeder.seed(SEAT_3_CLUB, SEAT_UNDER_TEST);

        assertThat(flightDates())
                .containsExactlyInAnyOrderElementsOf(
                        EXPECTED_FLYING_DAYS_BEFORE_THE_RUN_DATE.stream()
                                .map(RUN_DATE::minusDays)
                                .map(LocalDate.class::cast)
                                .toList());
        assertThat(reservationStartDates())
                .containsExactlyInAnyOrderElementsOf(
                        EXPECTED_RESERVATION_DAYS_AFTER_THE_RUN_DATE.stream()
                                .map(RUN_DATE::plusDays)
                                .map(LocalDate.class::cast)
                                .toList());
        assertThat(planningDates())
                .containsExactly(
                        RUN_DATE.plusDays(EXPECTED_PLANNING_DAY_DAYS_AFTER_THE_RUN_DATE));
    }

    private List<LocalDate> flightDates() {
        return jdbc.queryForList("SELECT flight_date FROM t_flight "
                        + "WHERE operating_club_id = ?::uuid AND deleted_on IS NULL",
                        java.sql.Date.class, SEAT_3_CLUB.toString()).stream()
                .map(java.sql.Date::toLocalDate)
                .distinct()
                .toList();
    }

    private List<LocalDate> reservationStartDates() {
        return jdbc.queryForList("SELECT reservation_start FROM t_aircraft_reservation "
                        + "WHERE operating_club_id = ?::uuid AND deleted_on IS NULL",
                        OffsetDateTime.class, SEAT_3_CLUB.toString()).stream()
                .map(start -> start.atZoneSameInstant(ZoneOffset.UTC).toLocalDate())
                .distinct()
                .toList();
    }

    private List<LocalDate> planningDates() {
        return jdbc.queryForList("SELECT planning_date FROM t_planning_day "
                        + "WHERE operating_club_id = ?::uuid AND deleted_on IS NULL",
                        java.sql.Date.class, SEAT_3_CLUB.toString()).stream()
                .map(java.sql.Date::toLocalDate)
                .toList();
    }
}

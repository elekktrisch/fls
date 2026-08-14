package ch.alpenflight.server.migration;

import static ch.alpenflight.server.testsupport.MigrationAssertions.assertTableExists;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class TenantCatalogConsistencyTest {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::jdbcUrl);
        r.add("spring.datasource.username", POSTGRES::username);
        r.add("spring.datasource.password", POSTGRES::password);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.url", POSTGRES::jdbcUrl);
        r.add("spring.flyway.user", POSTGRES::username);
        r.add("spring.flyway.password", POSTGRES::password);
    }

    @Autowired DataSource dataSource;

    @Test
    void every_s013_tenant_scoped_table_has_operating_club_id_uuid_not_null() throws Exception {
        List<String> tables = List.of("t_flight", "t_flight_type", "t_article");
        try (Connection conn = dataSource.getConnection()) {
            for (String t : tables) {
                try (var stmt = conn.prepareStatement(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name=? AND column_name='operating_club_id'")) {
                    stmt.setString(1, t);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next()).as("%s must have operating_club_id", t).isTrue();
                        assertThat(rs.getString("data_type")).isEqualTo("uuid");
                        assertThat(rs.getString("is_nullable"))
                                .as("%s.operating_club_id must be NOT NULL", t)
                                .isEqualTo("NO");
                    }
                }
            }
        }
    }

    @Test
    void every_s013_reference_table_has_no_operating_club_id() throws Exception {
        List<String> refs = List.of(
                "t_aircraft_type", "t_aircraft_state", "t_location_type",
                "t_flight_crew_type", "t_flight_process_state",
                "t_flight_cost_balance_type");
        try (Connection conn = dataSource.getConnection()) {
            for (String t : refs) {
                assertTableExists(conn, t);
                try (var stmt = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name=? "
                                + "AND column_name IN ('operating_club_id', 'club_id')")) {
                    stmt.setString(1, t);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next())
                                .as("reference table %s must NOT carry operating_club_id / club_id", t)
                                .isFalse();
                    }
                }
            }
        }
    }

    @Test
    void location_has_club_id_uuid_not_null() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "t_location");
            try (var stmt = conn.prepareStatement(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='t_location' "
                            + "AND column_name='club_id'")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).as("location must carry club_id (S-049b)").isTrue();
                    assertThat(rs.getString("data_type")).isEqualTo("uuid");
                    assertThat(rs.getString("is_nullable"))
                            .as("location.club_id must be NOT NULL")
                            .isEqualTo("NO");
                }
            }
        }
    }

    @Test
    void location_icao_is_unique_per_club_not_globally() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT indexdef FROM pg_indexes "
                                + "WHERE schemaname='public' AND tablename='t_location' "
                                + "AND indexname='ux_location_club_icao'")) {
            assertThat(rs.next())
                    .as("ux_location_club_icao partial UNIQUE must exist (S-049b)")
                    .isTrue();
            String def = rs.getString("indexdef");
            assertThat(def)
                    .as("partial UNIQUE keys on (club_id, icao_code) WHERE icao_code IS NOT NULL")
                    .contains("club_id")
                    .contains("icao_code");
        }
    }

    @Test
    void inoutbound_point_has_no_own_club_id() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "t_inoutbound_point");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='t_inoutbound_point' "
                            + "AND column_name IN ('club_id', 'operating_club_id')")) {
                assertThat(rs.next())
                        .as("inoutbound_point inherits tenancy via parent Location — no own club_id")
                        .isFalse();
            }
        }
    }

    @Test
    void aircraft_owner_club_id_is_nullable_ownership_metadata() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_aircraft' "
                                + "AND column_name='owner_club_id'")) {
            assertThat(rs.next()).as("aircraft.owner_club_id must exist").isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("uuid");
            assertThat(rs.getString("is_nullable"))
                    .as("aircraft.owner_club_id must be NULLABLE (ownership metadata, not tenancy)")
                    .isEqualTo("YES");
        }
    }

    @Test
    void aircraft_managing_club_id_is_required_metadata_not_null() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_aircraft' "
                                + "AND column_name='managing_club_id'")) {
            assertThat(rs.next()).as("aircraft.managing_club_id must exist").isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("uuid");
            assertThat(rs.getString("is_nullable"))
                    .as("aircraft.managing_club_id must be NOT NULL (manager-club gate)")
                    .isEqualTo("NO");
        }
    }

    @Test
    void every_tenant_scoped_table_has_club_id_uuid_not_null() throws Exception {
        List<String> tables = List.of(
                "t_club_extension", "t_member_state", "t_person_category");
        try (Connection conn = dataSource.getConnection()) {
            for (String t : tables) {
                try (var stmt = conn.prepareStatement(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name=? AND column_name='club_id'")) {
                    stmt.setString(1, t);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next()).as("%s must have club_id", t).isTrue();
                        assertThat(rs.getString("data_type")).isEqualTo("uuid");
                        assertThat(rs.getString("is_nullable"))
                                .as("%s.club_id must be NOT NULL", t)
                                .isEqualTo("NO");
                    }
                }
            }
        }
    }

    @Test
    void cross_tenant_tables_have_no_club_id() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "t_person");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='t_person' "
                            + "AND column_name='club_id'")) {
                assertThat(rs.next())
                        .as("person must NOT carry a club_id column (cross-tenant sacred cow)")
                        .isFalse();
            }
        }
    }

    @Test
    void reference_tables_have_no_club_id() throws Exception {
        List<String> refs = List.of(
                "t_country", "t_language", "t_start_type",
                "t_length_unit_type", "t_elevation_unit_type", "t_counter_unit_type",
                "t_club_state", "t_extension_type");
        try (Connection conn = dataSource.getConnection()) {
            for (String t : refs) {
                assertTableExists(conn, t);
                try (var stmt = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name=? AND column_name='club_id'")) {
                    stmt.setString(1, t);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next())
                                .as("reference table %s must NOT carry a club_id", t)
                                .isFalse();
                    }
                }
            }
        }
    }

    @Test
    void every_s014_tenant_scoped_table_has_operating_club_id_uuid_not_null() throws Exception {
        List<String> tables = List.of(
                "t_aircraft_reservation", "t_aircraft_reservation_type",
                "t_planning_day", "t_planning_day_assignment", "t_planning_day_assignment_type",
                "t_accounting_rule_filter",
                "t_delivery", "t_delivery_item",
                "t_delivery_creation_test", "t_delivery_creation_test_item");
        try (Connection conn = dataSource.getConnection()) {
            for (String t : tables) {
                try (var stmt = conn.prepareStatement(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name=? AND column_name='operating_club_id'")) {
                    stmt.setString(1, t);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next()).as("%s must have operating_club_id", t).isTrue();
                        assertThat(rs.getString("data_type")).isEqualTo("uuid");
                        assertThat(rs.getString("is_nullable"))
                                .as("%s.operating_club_id must be NOT NULL", t)
                                .isEqualTo("NO");
                    }
                }
            }
        }
    }

    @Test
    void every_s014_system_global_reference_table_has_no_operating_club_id() throws Exception {
        List<String> refs = List.of("t_accounting_rule_filter_type", "t_accounting_unit_type");
        try (Connection conn = dataSource.getConnection()) {
            for (String t : refs) {
                assertTableExists(conn, t);
                try (var stmt = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name=? "
                                + "AND column_name IN ('operating_club_id', 'club_id')")) {
                    stmt.setString(1, t);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next())
                                .as("system-global reference table %s must NOT carry operating_club_id / club_id", t)
                                .isFalse();
                    }
                }
            }
        }
    }

    @Test
    void aircraft_reservation_type_reclassified_to_tenant_scoped() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "t_aircraft_reservation_type");
            try (var stmt = conn.prepareStatement(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='t_aircraft_reservation_type' "
                            + "AND column_name='operating_club_id'")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next())
                            .as("aircraft_reservation_type must carry operating_club_id per legacy AircraftReservationType.cs:33 ClubId NOT NULL")
                            .isTrue();
                    assertThat(rs.getString("data_type")).isEqualTo("uuid");
                    assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                }
            }
        }
    }

    @Test
    void planning_day_assignment_type_reclassified_to_tenant_scoped() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "t_planning_day_assignment_type");
            try (var stmt = conn.prepareStatement(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='t_planning_day_assignment_type' "
                            + "AND column_name='operating_club_id'")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next())
                            .as("planning_day_assignment_type must carry operating_club_id per legacy PlanningDayAssignmentType.cs:21 ClubId NOT NULL")
                            .isTrue();
                    assertThat(rs.getString("data_type")).isEqualTo("uuid");
                    assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                }
            }
        }
    }
}

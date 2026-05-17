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

/**
 * Asserts the live schema produced by the baseline migrations (V2 + V3)
 * matches the tenant-scope catalog's classification: tenant-scoped tables
 * carry a {@code club_id} / {@code operating_club_id} NOT NULL, cross-tenant
 * tables don't, reference tables don't. Pure-YAML assertions over
 * {@code next/database/tenant-rules.yaml} live in
 * {@link TenantCatalogYamlTest} (plain JUnit; no Docker dep).
 */
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
        // S-013 tenant-scoped tables carry `operating_club_id` (renamed from
        // legacy `ClubId` / `OwnerClubId` per the new-schema convention).
        // flight_crew is aggregate-internal to flight and inherits via FK (no
        // own operating_club_id column).
        List<String> tables = List.of("flight", "flight_type", "article");
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
                "aircraft_type", "aircraft_state", "location_type",
                "flight_crew_type", "flight_process_state", "flight_air_state",
                "flight_cost_balance_type");
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

    /** Sacred cow: location is cross-tenant shared; no club_id / operating_club_id. */
    @Test
    void location_has_no_club_id() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "location");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='location' "
                            + "AND column_name IN ('club_id', 'operating_club_id')")) {
                assertThat(rs.next())
                        .as("location must NOT carry a club_id / operating_club_id (sacred cow)")
                        .isFalse();
            }
        }
    }

    /** Aircraft is CROSS_TENANT per 2026-05-16 amendment — owner_club_id is nullable. */
    @Test
    void aircraft_tenant_column_renamed_to_owner_club_id_nullable() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='aircraft' "
                                + "AND column_name='owner_club_id'")) {
            assertThat(rs.next()).as("aircraft.owner_club_id must exist").isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("uuid");
            assertThat(rs.getString("is_nullable"))
                    .as("aircraft.owner_club_id must be NULLABLE (Aircraft is cross-tenant)")
                    .isEqualTo("YES");
        }
    }

    @Test
    void every_tenant_scoped_table_has_club_id_uuid_not_null() throws Exception {
        List<String> tables = List.of(
                "club_extension", "member_state", "person_category");
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
        // Person is the cross-tenant cluster. Test guards against a future
        // implementer accidentally adding club_id to Person; precondition is
        // that the person table exists at all (otherwise the absence-check
        // would trivially pass when the migration is missing).
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "person");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='person' "
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
                "country", "language", "start_type",
                "length_unit_type", "elevation_unit_type", "counter_unit_type",
                "club_state", "extension_type", "role");
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

    /**
     * S-014 tenant-scoped tables — the 5 aggregate roots, 3 internal entities
     * (denormalized operating_club_id), and 2 per-club reclassified ref tables
     * (aircraft_reservation_type + planning_day_assignment_type). All carry
     * operating_club_id uuid NOT NULL.
     */
    @Test
    void every_s014_tenant_scoped_table_has_operating_club_id_uuid_not_null() throws Exception {
        List<String> tables = List.of(
                "aircraft_reservation", "aircraft_reservation_type",
                "planning_day", "planning_day_assignment", "planning_day_assignment_type",
                "accounting_rule_filter",
                "delivery", "delivery_item",
                "delivery_creation_test", "delivery_creation_test_item");
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

    /** S-014 system-global reference tables — accounting_rule_filter_type + accounting_unit_type. */
    @Test
    void every_s014_system_global_reference_table_has_no_operating_club_id() throws Exception {
        List<String> refs = List.of("accounting_rule_filter_type", "accounting_unit_type");
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

    /** Reclassification: AircraftReservationTypes was reference at S-011; S-014 promotes to per-club. */
    @Test
    void aircraft_reservation_type_reclassified_to_tenant_scoped() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "aircraft_reservation_type");
            try (var stmt = conn.prepareStatement(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='aircraft_reservation_type' "
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

    /** Reclassification: PlanningDayAssignmentTypes was reference at S-011; S-014 promotes to per-club. */
    @Test
    void planning_day_assignment_type_reclassified_to_tenant_scoped() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "planning_day_assignment_type");
            try (var stmt = conn.prepareStatement(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='planning_day_assignment_type' "
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

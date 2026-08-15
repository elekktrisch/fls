package ch.alpenflight.server.migration;

import static ch.alpenflight.server.testsupport.MigrationAssertions.assertTableExists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.alpenflight.server.testsupport.MigrationAssertions;
import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
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
class ReservationsBaselineIntegrationTest {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;
    private static JsonNode canonicalSeeds;

    private static final List<String> S014_DOMAIN_TABLES = List.of(
            "t_aircraft_reservation", "t_aircraft_reservation_type",
            "t_planning_day", "t_planning_day_assignment", "t_planning_day_assignment_type",
            "t_accounting_rule_filter", "t_accounting_rule_filter_type", "t_accounting_unit_type",
            "t_delivery", "t_delivery_item",
            "t_delivery_creation_test", "t_delivery_creation_test_item");

    private static final List<String> S014_INTERNAL_ENTITIES = List.of(
            "t_delivery_item", "t_planning_day_assignment", "t_delivery_creation_test_item");

    private static final List<String> S014_TENANT_SCOPED_TABLES = List.of(
            "t_aircraft_reservation", "t_aircraft_reservation_type",
            "t_planning_day", "t_planning_day_assignment", "t_planning_day_assignment_type",
            "t_accounting_rule_filter",
            "t_delivery", "t_delivery_item",
            "t_delivery_creation_test", "t_delivery_creation_test_item");

    private static final List<String> S014_SYSTEM_GLOBAL_REF_TABLES = List.of(
            "t_accounting_rule_filter_type", "t_accounting_unit_type");

    private static final String DEV_SEED_UUID_BAND_PREFIX = "019e30c3-";

    @BeforeAll
    static void loadCanonicalSeeds() throws Exception {
        try (InputStream in = ReservationsBaselineIntegrationTest.class
                .getResourceAsStream("/reference-seeds-canonical-uuids.json")) {
            canonicalSeeds = new ObjectMapper().readTree(in);
        }
    }

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
    void all_12_domain_tables_present() throws Exception {
        Set<String> actual = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
            while (rs.next()) actual.add(rs.getString(1));
        }
        assertThat(actual)
                .as("V4 migration must create all 12 S-014 domain tables")
                .containsAll(S014_DOMAIN_TABLES);
        assertThat(actual)
                .as("V53 dropped the never-wired t_club_delivery_number_counter; "
                        + "delivery_number is externally-supplied free text, no allocator")
                .doesNotContain("t_club_delivery_number_counter");
    }

    @Test
    void btree_gist_extension_installed() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT 1 FROM pg_extension WHERE extname = 'btree_gist'")) {
            assertThat(rs.next())
                    .as("btree_gist extension required for composite GiST index on aircraft_reservation")
                    .isTrue();
        }
    }

    @Test
    void all_pk_columns_are_uuid_not_null() throws Exception {
        record PkRow(String table, String column, String type, String nullable) {}
        List<String> allTables = new ArrayList<>(S014_DOMAIN_TABLES);
        List<PkRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement("""
                        SELECT t.table_name, c.column_name, c.data_type, c.is_nullable
                        FROM information_schema.tables t
                        JOIN information_schema.table_constraints tc
                          ON tc.table_schema = t.table_schema AND tc.table_name = t.table_name
                          AND tc.constraint_type = 'PRIMARY KEY'
                        JOIN information_schema.key_column_usage k
                          ON k.constraint_schema = tc.constraint_schema
                          AND k.constraint_name = tc.constraint_name
                        JOIN information_schema.columns c
                          ON c.table_schema = k.table_schema
                          AND c.table_name = k.table_name
                          AND c.column_name = k.column_name
                        WHERE t.table_schema = 'public' AND t.table_name = ANY (?)
                        """)) {
            stmt.setArray(1, stmt.getConnection().createArrayOf("text", allTables.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PkRow(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        }
        Set<String> seenTables = new LinkedHashSet<>();
        for (PkRow row : rows) seenTables.add(row.table());
        assertThat(seenTables)
                .as("every S-014 table must contribute a PK row to the join")
                .containsExactlyInAnyOrderElementsOf(allTables);
        for (PkRow row : rows) {
            assertThat(row.type())
                    .as("PK %s.%s must be uuid (ADR 0019)", row.table(), row.column())
                    .isEqualTo("uuid");
            assertThat(row.nullable())
                    .as("PK %s.%s must be NOT NULL", row.table(), row.column())
                    .isEqualTo("NO");
        }
    }

    @Test
    void all_fk_columns_are_uuid() throws Exception {
        List<String> allTables = new ArrayList<>(S014_DOMAIN_TABLES);
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement("""
                        SELECT c.table_name, k.column_name, col.data_type
                        FROM information_schema.table_constraints c
                        JOIN information_schema.key_column_usage k
                          ON k.constraint_schema = c.constraint_schema
                          AND k.constraint_name = c.constraint_name
                        JOIN information_schema.columns col
                          ON col.table_schema = k.table_schema
                          AND col.table_name = k.table_name
                          AND col.column_name = k.column_name
                        WHERE c.constraint_type = 'FOREIGN KEY'
                          AND c.table_schema = 'public'
                          AND c.table_name = ANY (?)
                        """)) {
            stmt.setArray(1, stmt.getConnection().createArrayOf("text", allTables.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    assertThat(rs.getString("data_type"))
                            .as("FK %s.%s must be uuid (ADR 0019)",
                                    rs.getString("table_name"), rs.getString("column_name"))
                            .isEqualTo("uuid");
                }
            }
        }
    }

    @Test
    void tenant_scoped_tables_have_operating_club_id_not_null_fk_restrict() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String t : S014_TENANT_SCOPED_TABLES) {
                assertColumnNotNull(conn, t, "operating_club_id", "uuid");
                assertFkDeleteRule(t, "operating_club_id", "RESTRICT");
            }
        }
    }

    @Test
    void system_global_reference_tables_have_no_operating_club_id() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String t : S014_SYSTEM_GLOBAL_REF_TABLES) {
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
    void delivery_process_state_id_is_smallint() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_delivery' "
                                + "AND column_name='process_state_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .as("delivery.process_state_id must be SMALLINT (column reshaped from legacy);"
                            + " allowed-value set moves to Delivery.ProcessState enum at S-022 per ADR 0022")
                    .isEqualTo("smallint");
        }
    }

    @Test
    void delivery_has_9_recipient_snapshot_columns() throws Exception {
        List<String> required = List.of(
                "recipient_name", "recipient_firstname", "recipient_lastname",
                "recipient_address_line1", "recipient_address_line2",
                "recipient_zip_code", "recipient_city", "recipient_country_name",
                "recipient_person_club_member_number");
        try (Connection conn = dataSource.getConnection()) {
            for (String col : required) {
                try (var stmt = conn.prepareStatement(
                        "SELECT data_type FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_delivery' AND column_name=?")) {
                    stmt.setString(1, col);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next())
                                .as("delivery.%s frozen-recipient snapshot column must exist", col)
                                .isTrue();
                        assertThat(rs.getString(1))
                                .as("delivery.%s must be a string type (frozen snapshot, NOT FK)", col)
                                .isEqualTo("character varying");
                    }
                }
            }
        }
    }

    @Test
    void delivery_recipient_person_fk_on_delete_set_null() throws Exception {
        assertFkDeleteRule("t_delivery", "recipient_person_id", "SET NULL");
    }

    @Test
    void delivery_flight_fk_on_delete_restrict() throws Exception {
        assertFkDeleteRule("t_delivery", "flight_id", "RESTRICT");
    }

    @Test
    void delivery_number_carries_no_unique_index() throws Exception {
        List<String> defs = indexDefs("t_delivery");
        assertThat(defs)
                .as("delivery_number is free-text (Proffix-supplied, collisions exist) — "
                        + "it must NOT carry a UNIQUE index")
                .noneMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique") && lc.contains("delivery_number");
                });
    }

    @Test
    void delivery_number_is_free_text_and_permits_duplicates() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String club = seedMinimalClub(conn, "TST_DLV_A");
                insertDeliveryWithNumber(conn,
                        newDeterministicUuid("t_delivery", "free_1"), club, "Workflow 2026-06-25T08:00:00", 10);
                insertDeliveryWithNumber(conn,
                        newDeterministicUuid("t_delivery", "free_2"), club, "Workflow 2026-06-25T08:00:00", 10);
            } finally {
                conn.rollback();
            }
        }
    }


    @Test
    void delivery_batch_id_unique_per_club_except_the_default_zero() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String clubA = seedMinimalClub(conn, "TST_BUA");
                String clubB = seedMinimalClub(conn, "TST_BUB");
                insertDeliveryWithBatch(conn, newDeterministicUuid("t_delivery", "batch_A_42"), clubA, 42);
                insertDeliveryWithBatch(conn, newDeterministicUuid("t_delivery", "batch_B_42"), clubB, 42);
                insertDeliveryWithBatch(conn, newDeterministicUuid("t_delivery", "batch_A_0_first"), clubA, 0);
                insertDeliveryWithBatch(conn, newDeterministicUuid("t_delivery", "batch_A_0_second"), clubA, 0);

                Throwable dup = catchThrowable(() -> insertDeliveryWithBatch(
                        conn, newDeterministicUuid("t_delivery", "batch_A_42_dup"), clubA, 42));
                assertThat(dup).isInstanceOf(SQLException.class);
                assertThat(((SQLException) dup).getSQLState())
                        .as("SQLSTATE 23505 — same non-zero batch_id within same club")
                        .isEqualTo("23505");
            } finally {
                conn.rollback();
            }
        }
    }


    @Test
    void delivery_item_article_fk_restrict() throws Exception {
        assertFkDeleteRule("t_delivery_item", "article_id", "RESTRICT");
    }

    @Test
    void delivery_item_delivery_fk_cascade() throws Exception {
        assertFkDeleteRule("t_delivery_item", "delivery_id", "CASCADE");
    }

    @Test
    void delivery_item_position_unique_per_delivery_partial() throws Exception {
        List<String> defs = indexDefs("t_delivery_item");
        assertThat(defs)
                .as("delivery_item must carry partial UNIQUE (delivery_id, position) WHERE deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique") && lc.contains("delivery_id")
                            && lc.contains("position") && lc.contains("deleted_on is null");
                });
    }



    @Test
    void aircraft_reservation_has_generated_tstzrange_column() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT a.attgenerated, t.typname FROM pg_attribute a "
                                + "JOIN pg_type t ON t.oid = a.atttypid "
                                + "WHERE a.attrelid = 't_aircraft_reservation'::regclass "
                                + "AND a.attname = 'reservation_range'")) {
            assertThat(rs.next()).as("reservation_range column must exist").isTrue();
            assertThat(rs.getString("attgenerated"))
                    .as("reservation_range must be GENERATED STORED")
                    .isEqualTo("s");
            assertThat(rs.getString("typname"))
                    .as("reservation_range must be tstzrange (tsrange requires TIMESTAMP — "
                            + "TIMESTAMPTZ::timestamp cast is not IMMUTABLE; tstzrange takes "
                            + "TIMESTAMPTZ directly and is immutable)")
                    .isEqualTo("tstzrange");
        }
    }

    @Test
    void aircraft_reservation_gist_index_on_aircraft_range_present() throws Exception {
        List<String> defs = indexDefs("t_aircraft_reservation");
        assertThat(defs)
                .as("aircraft_reservation must carry GiST partial index on (aircraft_id, reservation_range)")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("using gist")
                            && lc.contains("aircraft_id")
                            && lc.contains("reservation_range")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void aircraft_reservation_aircraft_id_cross_tenant_column_comment() throws Exception {
        String comment = columnComment("t_aircraft_reservation", "aircraft_id");
        assertThat(comment)
                .as("aircraft_reservation.aircraft_id COMMENT must flag cross-tenant per amendment")
                .isNotNull()
                .containsIgnoringCase("cross-tenant");
    }

    @Test
    void aircraft_reservation_aircraft_fk_restrict() throws Exception {
        assertFkDeleteRule("t_aircraft_reservation", "aircraft_id", "RESTRICT");
    }

    @Test
    void aircraft_reservation_pilot_fk_restrict() throws Exception {
        assertFkDeleteRule("t_aircraft_reservation", "pilot_person_id", "RESTRICT");
    }

    @Test
    void aircraft_reservation_second_crew_fk_set_null() throws Exception {
        assertFkDeleteRule("t_aircraft_reservation", "second_crew_person_id", "SET NULL");
    }

    @Test
    void aircraft_reservation_location_fk_restrict() throws Exception {
        assertFkDeleteRule("t_aircraft_reservation", "location_id", "RESTRICT");
    }


    @Test
    void planning_day_unique_per_club_date_location_partial() throws Exception {
        List<String> defs = indexDefs("t_planning_day");
        assertThat(defs)
                .as("planning_day must carry partial UNIQUE (operating_club_id, planning_date, location_id) WHERE deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique")
                            && lc.contains("operating_club_id")
                            && lc.contains("planning_date")
                            && lc.contains("location_id")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void planning_day_assignment_planning_day_fk_cascade() throws Exception {
        assertFkDeleteRule("t_planning_day_assignment", "planning_day_id", "CASCADE");
    }

    @Test
    void planning_day_assignment_person_fk_restrict() throws Exception {
        assertFkDeleteRule("t_planning_day_assignment", "assigned_person_id", "RESTRICT");
    }

    @Test
    void planning_day_assignment_unique_composite_partial() throws Exception {
        List<String> defs = indexDefs("t_planning_day_assignment");
        assertThat(defs)
                .as("planning_day_assignment must carry partial UNIQUE composite")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique")
                            && lc.contains("planning_day_id")
                            && lc.contains("assigned_person_id")
                            && lc.contains("assignment_type_id")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void planning_day_assignment_has_operating_club_id_denormalized() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertColumnNotNull(conn, "t_planning_day_assignment", "operating_club_id", "uuid");
        }
    }


    @Test
    void accounting_rule_filter_filter_config_is_jsonb_not_null() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_accounting_rule_filter' "
                                + "AND column_name='filter_config'")) {
            assertThat(rs.next()).as("filter_config column must exist").isTrue();
            assertThat(rs.getString("data_type"))
                    .as("filter_config must be jsonb")
                    .isEqualTo("jsonb");
            assertThat(rs.getString("is_nullable"))
                    .as("filter_config must be NOT NULL (DEFAULT '{}')")
                    .isEqualTo("NO");
        }
    }

    @Test
    void accounting_rule_filter_gin_index_on_filter_config_jsonb_path_ops() throws Exception {
        List<String> defs = indexDefs("t_accounting_rule_filter");
        assertThat(defs)
                .as("accounting_rule_filter must carry GIN index on filter_config jsonb_path_ops (admin search)")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("using gin")
                            && lc.contains("filter_config")
                            && lc.contains("jsonb_path_ops");
                });
    }

    @Test
    void accounting_rule_filter_hot_index_on_club_active_sort() throws Exception {
        List<String> defs = indexDefs("t_accounting_rule_filter");
        assertThat(defs)
                .as("accounting_rule_filter must carry hot index on (operating_club_id, is_active, sort_indicator) WHERE deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("operating_club_id")
                            && lc.contains("is_active")
                            && lc.contains("sort_indicator")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void accounting_rule_filter_sort_indicator_unique_per_club_partial() throws Exception {
        List<String> defs = indexDefs("t_accounting_rule_filter");
        assertThat(defs)
                .as("accounting_rule_filter must carry partial UNIQUE (operating_club_id, sort_indicator) WHERE deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique")
                            && lc.contains("operating_club_id")
                            && lc.contains("sort_indicator")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void accounting_rule_filter_filter_type_fk_restrict() throws Exception {
        assertFkDeleteRule("t_accounting_rule_filter", "filter_type_id", "RESTRICT");
    }


    @Test
    void accounting_rule_filter_type_seeded_with_10_canonical_codes() throws Exception {
        List<String> expectedCodes = List.of(
                "RECIPIENT", "NO_LANDING_TAX", "FLIGHT_TIME", "INSTRUCTOR_FEE",
                "ADDITIONAL_FUEL_FEE", "LANDING_TAX", "VSF_FEE", "ENGINE_TIME",
                "DO_NOT_INVOICE", "START_TAX");
        assertSeededCodes("t_accounting_rule_filter_type", expectedCodes);
        for (String code : expectedCodes) {
            assertCodeMapsToUuid("t_accounting_rule_filter_type", code,
                    canonicalSeedUuid("t_accounting_rule_filter_type", "code", code));
        }
    }

    @Test
    void accounting_rule_filter_type_legacy_int_ids_match_legacy() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT legacy_int_id FROM t_accounting_rule_filter_type ORDER BY legacy_int_id")) {
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getInt(1));
            assertThat(ids)
                    .as("legacy AccountingRuleFilterTypeId enum: 5 (DoNotInvoice), 10, 20, 30, "
                            + "40, 50, 55 (StartTax), 60, 70, 80 — all 10 must resolve so a "
                            + "real club's type-5/55 filter migrates without an FK 23503")
                    .containsExactly(5, 10, 20, 30, 40, 50, 55, 60, 70, 80);
        }
    }

    @Test
    void accounting_unit_type_seeded_with_4_canonical_codes() throws Exception {
        List<String> expectedCodes = List.of("MINUTES", "SECONDS", "LANDINGS", "START_OR_FLIGHT");
        assertSeededCodes("t_accounting_unit_type", expectedCodes);
        for (String code : expectedCodes) {
            assertCodeMapsToUuid("t_accounting_unit_type", code,
                    canonicalSeedUuid("t_accounting_unit_type", "code", code));
        }
    }

    @Test
    void accounting_unit_type_legacy_int_ids_match_legacy() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT legacy_int_id FROM t_accounting_unit_type ORDER BY legacy_int_id")) {
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getInt(1));
            assertThat(ids).containsExactly(10, 20, 30, 40);
        }
    }

    @Test
    void aircraft_reservation_type_only_the_dev_seed_present() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT id::text, operating_club_id::text, reservation_type_name "
                                + "FROM t_aircraft_reservation_type "
                                + "WHERE id::text LIKE '" + DEV_SEED_UUID_BAND_PREFIX + "%'")) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
            }
            assertThat(rows)
                    .as("the ONLY seed-band t_aircraft_reservation_type row is the V31 dev/test "
                            + "seed (no structural V4 seeding); random-UUID rows from sibling ITs ignored")
                    .containsExactly(
                            "019e30c3-2c00-7400-8000-000000000001"
                                    + "|019e30c3-2c00-7001-8000-000000000001|Allgemein");
        }
    }

    @Test
    void planning_day_assignment_type_only_the_dev_seed_present() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT id::text, operating_club_id::text, assignment_type_name "
                                + "FROM t_planning_day_assignment_type "
                                + "WHERE id::text LIKE '" + DEV_SEED_UUID_BAND_PREFIX + "%' "
                                + "ORDER BY id::text")) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
            }
            String club = "019e30c3-2c00-7001-8000-000000000001";
            assertThat(rows)
                    .as("the ONLY seed-band t_planning_day_assignment_type rows are the V34 dev/test "
                            + "seed (no structural V4 seeding); random-UUID rows from sibling ITs ignored")
                    .containsExactly(
                            "019e30c3-2c00-7001-8000-0000000000d1|" + club + "|Segelflugleiter",
                            "019e30c3-2c00-7001-8000-0000000000d2|" + club + "|Schlepppilot",
                            "019e30c3-2c00-7001-8000-0000000000d3|" + club + "|Fluglehrer");
        }
    }


    @Test
    void delivery_creation_test_flight_fk_cascade() throws Exception {
        assertFkDeleteRule("t_delivery_creation_test", "flight_id", "CASCADE");
    }

    @Test
    void delivery_creation_test_unique_per_club_flight_partial() throws Exception {
        List<String> defs = indexDefs("t_delivery_creation_test");
        assertThat(defs)
                .as("delivery_creation_test must carry partial UNIQUE (operating_club_id, flight_id) WHERE deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique")
                            && lc.contains("operating_club_id")
                            && lc.contains("flight_id")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void delivery_creation_test_expected_delivery_is_jsonb_not_null() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_delivery_creation_test' "
                                + "AND column_name='expected_delivery'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("jsonb");
            assertThat(rs.getString("is_nullable")).isEqualTo("NO");
        }
    }

    @Test
    void delivery_creation_test_has_9_ignore_boolean_columns() throws Exception {
        List<String> required = List.of(
                "ignore_recipient_name", "ignore_recipient_address",
                "ignore_recipient_person_id", "ignore_recipient_club_member_number",
                "ignore_delivery_information", "ignore_additional_information",
                "ignore_item_positioning", "ignore_item_text",
                "ignore_item_additional_information");
        try (Connection conn = dataSource.getConnection()) {
            for (String col : required) {
                try (var stmt = conn.prepareStatement(
                        "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_delivery_creation_test' "
                                + "AND column_name=?")) {
                    stmt.setString(1, col);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next()).as("delivery_creation_test.%s must exist", col).isTrue();
                        assertThat(rs.getString("data_type"))
                                .as("delivery_creation_test.%s must be boolean", col)
                                .isEqualTo("boolean");
                        assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                        assertThat(rs.getString("column_default"))
                                .as("delivery_creation_test.%s must DEFAULT false", col)
                                .isEqualTo("false");
                    }
                }
            }
        }
    }

    @Test
    void delivery_creation_test_has_5_last_test_result_columns() throws Exception {
        List<String> required = List.of(
                "last_test_run_on", "last_test_successful", "last_test_result_message",
                "last_test_created_delivery", "last_test_matched_filter_ids");
        try (Connection conn = dataSource.getConnection()) {
            for (String col : required) {
                try (var stmt = conn.prepareStatement(
                        "SELECT 1 FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_delivery_creation_test' "
                                + "AND column_name=?")) {
                    stmt.setString(1, col);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next())
                                .as("delivery_creation_test.%s must exist", col).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void delivery_creation_test_matched_filter_ids_are_uuid_array_not_fk() throws Exception {
        for (String col : List.of("expected_matched_filter_ids", "last_test_matched_filter_ids")) {
            try (Connection conn = dataSource.getConnection();
                    var stmt = conn.prepareStatement(
                            "SELECT data_type, udt_name FROM information_schema.columns "
                                    + "WHERE table_schema='public' AND table_name='t_delivery_creation_test' "
                                    + "AND column_name=?")) {
                stmt.setString(1, col);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).as("%s must exist", col).isTrue();
                    assertThat(rs.getString("data_type"))
                            .as("%s must be ARRAY", col)
                            .isEqualTo("ARRAY");
                    assertThat(rs.getString("udt_name"))
                            .as("%s element type must be uuid (uuid[]) after V43", col)
                            .isEqualTo("_uuid");
                }
            }
        }
    }

    @Test
    void delivery_creation_test_item_fk_cascade() throws Exception {
        assertFkDeleteRule("t_delivery_creation_test_item", "delivery_creation_test_id", "CASCADE");
    }


    @Test
    void aggregate_root_column_comments_reference_adr_0019() throws Exception {
        record CommentExpect(String table, String prefix) {}
        List<CommentExpect> expects = List.of(
                new CommentExpect("t_aircraft_reservation",   "arv"),
                new CommentExpect("t_planning_day",           "pln"),
                new CommentExpect("t_accounting_rule_filter", "arf"),
                new CommentExpect("t_delivery",               "dlv"),
                new CommentExpect("t_delivery_creation_test", "dct"));
        for (CommentExpect e : expects) {
            String comment = columnComment(e.table(), "id");
            assertThat(comment)
                    .as("%s.id COMMENT must reference ADR 0019 + the '%s-' prefix", e.table(), e.prefix())
                    .isNotNull()
                    .containsIgnoringCase("ADR 0019")
                    .contains(e.prefix() + "-");
        }
    }

    @Test
    void non_aggregate_root_columns_do_not_carry_prefix_comments() throws Exception {
        for (String t : S014_INTERNAL_ENTITIES) {
            String comment = columnComment(t, "id");
            if (comment != null) {
                assertThat(comment)
                        .as("%s.id must NOT carry an aggregate-prefix External-form comment", t)
                        .doesNotContainIgnoringCase("External form:");
            }
        }
    }


    private static final Set<String> RETAINED_BUSINESS_LOGIC_CHECKS = Set.of(
            "ck_person_email_private_shape",
            "ck_person_email_business_shape",
            "ck_aircraft_spot_link_https");

    @Test
    void no_business_logic_check_constraints_in_baseline_schema() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT conrelid::regclass::text AS tbl, conname "
                                + "FROM pg_constraint "
                                + "WHERE contype = 'c' "
                                + "  AND connamespace = 'public'::regnamespace "
                                + "ORDER BY tbl, conname")) {
            List<String> unexpected = new ArrayList<>();
            while (rs.next()) {
                String name = rs.getString("conname");
                if (RETAINED_BUSINESS_LOGIC_CHECKS.contains(name)) continue;
                unexpected.add(rs.getString("tbl") + "." + name);
            }
            assertThat(unexpected)
                    .as("Schema must carry only the 3 retained CHECKs (email_private, email_business,"
                            + " spot_link_https); business-logic CHECKs migrated to aggregates per"
                            + " ADR 0022 directive 2")
                    .isEmpty();
        }
    }

    @Test
    void retained_checks_carry_adr_0022_retention_comment() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String name : RETAINED_BUSINESS_LOGIC_CHECKS) {
                try (var s = conn.prepareStatement(
                        "SELECT obj_description(c.oid, 'pg_constraint') "
                                + "FROM pg_constraint c WHERE c.conname = ?")) {
                    s.setString(1, name);
                    try (ResultSet rs = s.executeQuery()) {
                        assertThat(rs.next()).as("retained constraint %s must exist", name).isTrue();
                        String comment = rs.getString(1);
                        assertThat(comment)
                                .as("constraint %s must carry an `ADR 0022 retained: …` COMMENT marker", name)
                                .isNotNull()
                                .containsIgnoringCase("ADR 0022 retained");
                    }
                }
            }
        }
    }

    @Test
    void delivery_item_total_amount_column_absent_after_baseline() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT 1 FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_delivery_item' "
                                + "AND column_name='total_amount'")) {
            assertThat(rs.next())
                    .as("delivery_item.total_amount must not exist — calculation moves to"
                            + " DeliveryItem.totalAmount() at S-022")
                    .isFalse();
        }
    }

    @Test
    void ix_dli_delivery_does_not_include_total_amount_after_baseline() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT indexdef FROM pg_indexes "
                                + "WHERE schemaname='public' AND tablename='t_delivery_item' "
                                + "AND indexname='ix_dli_delivery'")) {
            assertThat(rs.next()).as("ix_dli_delivery must still exist").isTrue();
            String def = rs.getString(1);
            assertThat(def.toLowerCase(Locale.ROOT))
                    .as("ix_dli_delivery INCLUDE clause must not reference total_amount")
                    .doesNotContain("total_amount");
        }
    }



    private List<String> indexDefs(String table) throws SQLException {
        return MigrationAssertions.indexDefs(dataSource, table);
    }

    private String columnComment(String table, String column) throws SQLException {
        return MigrationAssertions.columnComment(dataSource, table, column);
    }

    private void assertColumnNotNull(Connection conn, String table, String column, String dataType)
            throws SQLException {
        MigrationAssertions.assertColumnNotNull(conn, table, column, dataType);
    }

    private void assertFkDeleteRule(String table, String column, String expectedRule) throws SQLException {
        MigrationAssertions.assertFkDeleteRule(dataSource, table, column, expectedRule);
    }

    private void assertSeededCodes(String table, List<String> expectedCodes) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement("SELECT code FROM " + table)) {
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> actual = new ArrayList<>();
                while (rs.next()) actual.add(rs.getString(1));
                assertThat(actual)
                        .as("%s must be seeded with the canonical row set", table)
                        .containsExactlyInAnyOrderElementsOf(expectedCodes);
            }
        }
    }

    private void assertCodeMapsToUuid(String table, String code, String expectedUuid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT id::text FROM " + table + " WHERE code = ?")) {
            stmt.setString(1, code);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).as("%s row code=%s must exist", table, code).isTrue();
                assertThat(rs.getString(1))
                        .as("%s row code=%s must have canonical UUID", table, code)
                        .isEqualTo(expectedUuid);
            }
        }
    }

    private static String canonicalSeedUuid(String table, String keyField, String keyValue) {
        String seedKey = table.startsWith("t_") ? table.substring(2) : table;
        for (JsonNode row : canonicalSeeds.get(seedKey)) {
            JsonNode keyNode = row.get(keyField);
            if (keyNode != null && keyValue.equals(keyNode.asText())) {
                return row.get("uuid").asText();
            }
        }
        throw new IllegalStateException(
                "no canonical UUID for " + table + " " + keyField + "=" + keyValue);
    }

    private String seedMinimalClub(Connection conn, String clubKey) throws SQLException {
        String chId = canonicalSeedUuid("t_country", "iso2", "CH");
        String clubStateActive = canonicalSeedUuid("t_club_state", "code", "ACTIVE");
        String clubId = newDeterministicUuid("t_club", clubKey);
        try (var s = conn.prepareStatement(
                "INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id) "
                        + "VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid)")) {
            s.setString(1, clubId);
            s.setString(2, "Test " + clubKey);
            s.setString(3, clubKey);
            s.setString(4, chId);
            s.setString(5, clubStateActive);
            s.executeUpdate();
        }
        return clubId;
    }

    private void insertDeliveryWithNumber(Connection conn, String id, String clubId,
            String deliveryNumber, int processStateId) throws SQLException {
        try (var s = conn.prepareStatement(
                "INSERT INTO t_delivery (id, operating_club_id, process_state_id, "
                        + "  delivery_number, delivered_on, recipient_lastname, recipient_firstname) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, now(), 'X', 'Y')")) {
            s.setString(1, id);
            s.setString(2, clubId);
            s.setInt(3, processStateId);
            s.setString(4, deliveryNumber);
            s.executeUpdate();
        }
    }

    private void insertDeliveryWithBatch(Connection conn, String id, String clubId, int batchId)
            throws SQLException {
        try (var s = conn.prepareStatement(
                "INSERT INTO t_delivery (id, operating_club_id, process_state_id, batch_id) "
                        + "VALUES (?::uuid, ?::uuid, 10, ?)")) {
            s.setString(1, id);
            s.setString(2, clubId);
            s.setInt(3, batchId);
            s.executeUpdate();
        }
    }

    private static String newDeterministicUuid(String table, String key) {
        int h = (table + ":" + key).hashCode();
        long abs = Math.abs((long) h);
        return String.format("00000000-0000-7fff-8000-%012x", abs);
    }
}

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

/**
 * Schema-shape assertions for the V4__reservations_planning_accounting
 * migration (S-014). Shares the Postgres container with the other
 * migration tests so Spring's context cache reuses the same boot.
 *
 * <p>Layer: SQL/Postgres-introspection via {@code information_schema} +
 * {@code pg_catalog}. The story is parity-by-reshape (no legacy URL/JSON
 * shape to preserve); reference-seed enum values pinned via legacy-code
 * citations rather than runtime fixtures.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class ReservationsBaselineIntegrationTest {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;
    private static JsonNode canonicalSeeds;

    /** The 12 in-scope domain tables (5 aggregate roots + 3 internal entities + 2 per-club ref + 2 system-global ref). */
    private static final List<String> S014_DOMAIN_TABLES = List.of(
            "aircraft_reservation", "aircraft_reservation_type",
            "planning_day", "planning_day_assignment", "planning_day_assignment_type",
            "accounting_rule_filter", "accounting_rule_filter_type", "accounting_unit_type",
            "delivery", "delivery_item",
            "delivery_creation_test", "delivery_creation_test_item");

    /** 3 internal-entity tables — no aggregate prefix; cross boundaries only via parent. */
    private static final List<String> S014_INTERNAL_ENTITIES = List.of(
            "delivery_item", "planning_day_assignment", "delivery_creation_test_item");

    /** TENANT_SCOPED tables in S-014 (10 = 5 roots + 3 internals + 2 per-club ref tables).
     * Each must carry operating_club_id uuid NOT NULL → club(id). */
    private static final List<String> S014_TENANT_SCOPED_TABLES = List.of(
            "aircraft_reservation", "aircraft_reservation_type",
            "planning_day", "planning_day_assignment", "planning_day_assignment_type",
            "accounting_rule_filter",
            "delivery", "delivery_item",
            "delivery_creation_test", "delivery_creation_test_item");

    /** SYSTEM_GLOBAL reference tables (no operating_club_id). */
    private static final List<String> S014_SYSTEM_GLOBAL_REF_TABLES = List.of(
            "accounting_rule_filter_type", "accounting_unit_type");

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

    // ============================================================================
    // Table presence + counter + extension
    // ============================================================================

    /** AC1 — all 12 domain tables + the club_delivery_number_counter operational table. */
    @Test
    void all_12_tables_plus_counter_present() throws Exception {
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
                .as("V4 migration must create the club_delivery_number_counter operational table")
                .contains("club_delivery_number_counter");
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
        allTables.add("club_delivery_number_counter");
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
        allTables.add("club_delivery_number_counter");
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
                // delivery_item / planning_day_assignment / delivery_creation_test_item
                // denormalize operating_club_id from their parent aggregate but still
                // FK directly to club(id) for symmetry with @TenantId at S-022.
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

    // ============================================================================
    // Delivery state machine + numbering invariants
    // ============================================================================

    @Test
    void delivery_process_state_id_smallint_check_in_10_20_30_99() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='delivery' "
                                + "AND column_name='process_state_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .as("delivery.process_state_id must be SMALLINT (new column reshaped from legacy)")
                    .isEqualTo("smallint");
        }
        List<String> checks = checkConstraintDefs("delivery");
        assertThat(checks)
                .as("delivery must CHECK process_state_id IN (10, 20, 30, 99)")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("process_state_id")
                            && lc.contains("10") && lc.contains("20") && lc.contains("30") && lc.contains("99");
                });
    }

    /** Live provocation: process_state_id=999 must be rejected by CHECK (SQLSTATE 23514). */
    @Test
    void delivery_process_state_id_999_rejected_by_check() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String clubId = seedMinimalClub(conn, "TST_DLV1");
                String deliveryId = newDeterministicUuid("delivery", "state_999_provoke");
                Throwable thrown = catchThrowable(() -> {
                    try (var s = conn.prepareStatement(
                            "INSERT INTO delivery (id, operating_club_id, process_state_id) "
                                    + "VALUES (?::uuid, ?::uuid, 999)")) {
                        s.setString(1, deliveryId);
                        s.setString(2, clubId);
                        s.executeUpdate();
                    }
                });
                assertThat(thrown).isInstanceOf(SQLException.class);
                assertThat(((SQLException) thrown).getSQLState())
                        .as("SQLSTATE 23514 (check_violation) on process_state_id=999")
                        .isEqualTo("23514");
            } finally {
                conn.rollback();
            }
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
                                + "WHERE table_schema='public' AND table_name='delivery' AND column_name=?")) {
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
        assertFkDeleteRule("delivery", "recipient_person_id", "SET NULL");
    }

    @Test
    void delivery_flight_fk_on_delete_restrict() throws Exception {
        assertFkDeleteRule("delivery", "flight_id", "RESTRICT");
    }

    @Test
    void delivery_unique_per_club_delivery_number_partial() throws Exception {
        List<String> defs = indexDefs("delivery");
        assertThat(defs)
                .as("delivery must carry partial UNIQUE (operating_club_id, delivery_number) WHERE delivery_number IS NOT NULL AND deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique")
                            && lc.contains("operating_club_id") && lc.contains("delivery_number")
                            && lc.contains("delivery_number is not null")
                            && lc.contains("deleted_on is null");
                });
    }

    /** Live provocation: same delivery_number in same club must collide; cross-club must succeed. */
    @Test
    void delivery_number_unique_within_club_but_not_across_clubs() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String clubA = seedMinimalClub(conn, "TST_DLV_A");
                String clubB = seedMinimalClub(conn, "TST_DLV_B");
                insertDeliveryWithNumber(conn,
                        newDeterministicUuid("delivery", "uniq_A_1"), clubA, 1, 10);
                insertDeliveryWithNumber(conn,
                        newDeterministicUuid("delivery", "uniq_B_1"), clubB, 1, 10);

                Throwable dup = catchThrowable(() -> insertDeliveryWithNumber(
                        conn, newDeterministicUuid("delivery", "uniq_A_1_dup"), clubA, 1, 10));
                assertThat(dup).isInstanceOf(SQLException.class);
                assertThat(((SQLException) dup).getSQLState())
                        .as("SQLSTATE 23505 (unique_violation) — same delivery_number within same club")
                        .isEqualTo("23505");
            } finally {
                conn.rollback();
            }
        }
    }

    /** Booked deliveries (state 20) MUST carry delivery_number — CHECK enforced. */
    @Test
    void delivery_booked_requires_delivery_number_check() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String clubId = seedMinimalClub(conn, "TST_BKD");
                Throwable thrown = catchThrowable(() -> {
                    try (var s = conn.prepareStatement(
                            "INSERT INTO delivery (id, operating_club_id, process_state_id, "
                                    + "  recipient_lastname, recipient_firstname) "
                                    + "VALUES (?::uuid, ?::uuid, 20, 'X', 'Y')")) {
                        s.setString(1, newDeterministicUuid("delivery", "booked_no_number"));
                        s.setString(2, clubId);
                        s.executeUpdate();
                    }
                });
                assertThat(thrown).isInstanceOf(SQLException.class);
                assertThat(((SQLException) thrown).getSQLState())
                        .as("SQLSTATE 23514 — Booked requires delivery_number")
                        .isEqualTo("23514");
            } finally {
                conn.rollback();
            }
        }
    }

    /** Booked deliveries MUST carry recipient_lastname + recipient_firstname snapshot. */
    @Test
    void delivery_booked_requires_recipient_snapshot_check() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String clubId = seedMinimalClub(conn, "TST_RCP");
                Throwable thrown = catchThrowable(() -> {
                    try (var s = conn.prepareStatement(
                            "INSERT INTO delivery (id, operating_club_id, process_state_id, delivery_number) "
                                    + "VALUES (?::uuid, ?::uuid, 20, 1)")) {
                        s.setString(1, newDeterministicUuid("delivery", "booked_no_recipient"));
                        s.setString(2, clubId);
                        s.executeUpdate();
                    }
                });
                assertThat(thrown).isInstanceOf(SQLException.class);
                assertThat(((SQLException) thrown).getSQLState())
                        .as("SQLSTATE 23514 — Booked requires recipient snapshot")
                        .isEqualTo("23514");
            } finally {
                conn.rollback();
            }
        }
    }

    /** Booked deliveries (state 20) MUST carry delivered_on per Swiss OR Art. 957a — CHECK enforced. */
    @Test
    void delivery_booked_requires_delivered_on_check() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String clubId = seedMinimalClub(conn, "TST_DON");
                Throwable thrown = catchThrowable(() -> {
                    try (var s = conn.prepareStatement(
                            "INSERT INTO delivery (id, operating_club_id, process_state_id, "
                                    + "  delivery_number, recipient_lastname, recipient_firstname) "
                                    + "VALUES (?::uuid, ?::uuid, 20, 1, 'X', 'Y')")) {
                        s.setString(1, newDeterministicUuid("delivery", "booked_no_delivered_on"));
                        s.setString(2, clubId);
                        s.executeUpdate();
                    }
                });
                assertThat(thrown).isInstanceOf(SQLException.class);
                assertThat(((SQLException) thrown).getSQLState())
                        .as("SQLSTATE 23514 — Booked requires delivered_on")
                        .isEqualTo("23514");
            } finally {
                conn.rollback();
            }
        }
    }

    /** delivery.batch_id < 0 must be rejected by CHECK. */
    @Test
    void delivery_batch_id_negative_rejected_by_check() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String clubId = seedMinimalClub(conn, "TST_BID");
                Throwable thrown = catchThrowable(() -> {
                    try (var s = conn.prepareStatement(
                            "INSERT INTO delivery (id, operating_club_id, process_state_id, batch_id) "
                                    + "VALUES (?::uuid, ?::uuid, 10, -1)")) {
                        s.setString(1, newDeterministicUuid("delivery", "batch_id_negative"));
                        s.setString(2, clubId);
                        s.executeUpdate();
                    }
                });
                assertThat(thrown).isInstanceOf(SQLException.class);
                assertThat(((SQLException) thrown).getSQLState())
                        .as("SQLSTATE 23514 — batch_id must be >= 0")
                        .isEqualTo("23514");
            } finally {
                conn.rollback();
            }
        }
    }

    /** delivery.batch_id partial UNIQUE per club: same non-zero batch_id collides; cross-club ok. */
    @Test
    void delivery_batch_id_partial_unique_per_club() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String clubA = seedMinimalClub(conn, "TST_BUA");
                String clubB = seedMinimalClub(conn, "TST_BUB");
                insertDeliveryWithBatch(conn, newDeterministicUuid("delivery", "batch_A_42"), clubA, 42);
                insertDeliveryWithBatch(conn, newDeterministicUuid("delivery", "batch_B_42"), clubB, 42);
                // Default batch_id=0 must NOT collide (predicate excludes it).
                insertDeliveryWithBatch(conn, newDeterministicUuid("delivery", "batch_A_0_first"), clubA, 0);
                insertDeliveryWithBatch(conn, newDeterministicUuid("delivery", "batch_A_0_second"), clubA, 0);

                Throwable dup = catchThrowable(() -> insertDeliveryWithBatch(
                        conn, newDeterministicUuid("delivery", "batch_A_42_dup"), clubA, 42));
                assertThat(dup).isInstanceOf(SQLException.class);
                assertThat(((SQLException) dup).getSQLState())
                        .as("SQLSTATE 23505 — same non-zero batch_id within same club")
                        .isEqualTo("23505");
            } finally {
                conn.rollback();
            }
        }
    }

    // ============================================================================
    // delivery_item money math + generated total_amount
    // ============================================================================

    @Test
    void delivery_item_total_amount_is_generated_always_stored() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT a.attgenerated FROM pg_attribute a "
                                + "WHERE a.attrelid = 'delivery_item'::regclass "
                                + "AND a.attname = 'total_amount'")) {
            assertThat(rs.next()).as("delivery_item.total_amount must exist").isTrue();
            assertThat(rs.getString(1))
                    .as("delivery_item.total_amount must be GENERATED STORED ('s')")
                    .isEqualTo("s");
        }
    }

    @Test
    void delivery_item_quantity_nonnegative_check() throws Exception {
        List<String> checks = checkConstraintDefs("delivery_item");
        assertThat(checks)
                .as("delivery_item must CHECK quantity >= 0")
                .anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("quantity")
                        && d.contains(">="));
    }

    @Test
    void delivery_item_unit_price_nonnegative_check() throws Exception {
        List<String> checks = checkConstraintDefs("delivery_item");
        assertThat(checks)
                .as("delivery_item must CHECK unit_price >= 0")
                .anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("unit_price")
                        && d.contains(">="));
    }

    @Test
    void delivery_item_discount_in_percent_range_check() throws Exception {
        List<String> checks = checkConstraintDefs("delivery_item");
        assertThat(checks)
                .as("delivery_item must CHECK discount_in_percent BETWEEN 0 AND 100")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("discount_in_percent")
                            && lc.contains("0") && lc.contains("100");
                });
    }

    @Test
    void delivery_item_article_fk_restrict() throws Exception {
        assertFkDeleteRule("delivery_item", "article_id", "RESTRICT");
    }

    @Test
    void delivery_item_delivery_fk_cascade() throws Exception {
        assertFkDeleteRule("delivery_item", "delivery_id", "CASCADE");
    }

    @Test
    void delivery_item_position_unique_per_delivery_partial() throws Exception {
        List<String> defs = indexDefs("delivery_item");
        assertThat(defs)
                .as("delivery_item must carry partial UNIQUE (delivery_id, position) WHERE deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique") && lc.contains("delivery_id")
                            && lc.contains("position") && lc.contains("deleted_on is null");
                });
    }

    // ============================================================================
    // aircraft_reservation (cross-tenant aircraft FK per amendment)
    // ============================================================================

    @Test
    void aircraft_reservation_end_after_start_check() throws Exception {
        List<String> checks = checkConstraintDefs("aircraft_reservation");
        assertThat(checks)
                .as("aircraft_reservation must CHECK reservation_end > reservation_start")
                .anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("reservation_end")
                        && d.toLowerCase(Locale.ROOT).contains("reservation_start")
                        && d.contains(">"));
    }

    @Test
    void aircraft_reservation_has_generated_tstzrange_column() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT a.attgenerated, t.typname FROM pg_attribute a "
                                + "JOIN pg_type t ON t.oid = a.atttypid "
                                + "WHERE a.attrelid = 'aircraft_reservation'::regclass "
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
        List<String> defs = indexDefs("aircraft_reservation");
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

    /**
     * 2026-05-16 Aircraft-cross-tenant amendment: aircraft_reservation.aircraft_id
     * is a cross-tenant FK; the column comment must say so explicitly so future
     * implementers + S-024 leakage CI know to bypass the @TenantId roster.
     */
    @Test
    void aircraft_reservation_aircraft_id_cross_tenant_column_comment() throws Exception {
        String comment = columnComment("aircraft_reservation", "aircraft_id");
        assertThat(comment)
                .as("aircraft_reservation.aircraft_id COMMENT must flag cross-tenant per amendment")
                .isNotNull()
                .containsIgnoringCase("cross-tenant");
    }

    @Test
    void aircraft_reservation_aircraft_fk_restrict() throws Exception {
        assertFkDeleteRule("aircraft_reservation", "aircraft_id", "RESTRICT");
    }

    @Test
    void aircraft_reservation_pilot_fk_restrict() throws Exception {
        assertFkDeleteRule("aircraft_reservation", "pilot_person_id", "RESTRICT");
    }

    @Test
    void aircraft_reservation_second_crew_fk_set_null() throws Exception {
        assertFkDeleteRule("aircraft_reservation", "second_crew_person_id", "SET NULL");
    }

    @Test
    void aircraft_reservation_location_fk_restrict() throws Exception {
        assertFkDeleteRule("aircraft_reservation", "location_id", "RESTRICT");
    }

    // ============================================================================
    // planning_day + assignment
    // ============================================================================

    @Test
    void planning_day_unique_per_club_date_location_partial() throws Exception {
        List<String> defs = indexDefs("planning_day");
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
        assertFkDeleteRule("planning_day_assignment", "planning_day_id", "CASCADE");
    }

    /** Sacred-cow cross-tenant Person FK: RESTRICT to preserve planning history. */
    @Test
    void planning_day_assignment_person_fk_restrict() throws Exception {
        assertFkDeleteRule("planning_day_assignment", "assigned_person_id", "RESTRICT");
    }

    @Test
    void planning_day_assignment_unique_composite_partial() throws Exception {
        List<String> defs = indexDefs("planning_day_assignment");
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
            assertColumnNotNull(conn, "planning_day_assignment", "operating_club_id", "uuid");
        }
    }

    // ============================================================================
    // accounting_rule_filter — jsonb config + GIN + sort_indicator
    // ============================================================================

    @Test
    void accounting_rule_filter_filter_config_is_jsonb_not_null() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='accounting_rule_filter' "
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
        List<String> defs = indexDefs("accounting_rule_filter");
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
        List<String> defs = indexDefs("accounting_rule_filter");
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
        List<String> defs = indexDefs("accounting_rule_filter");
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
        assertFkDeleteRule("accounting_rule_filter", "filter_type_id", "RESTRICT");
    }

    // ============================================================================
    // Reference seeds — assert against canonical-UUID JSON
    // ============================================================================

    @Test
    void accounting_rule_filter_type_seeded_with_8_canonical_codes() throws Exception {
        List<String> expectedCodes = List.of(
                "RECIPIENT", "NO_LANDING_TAX", "FLIGHT_TIME", "INSTRUCTOR_FEE",
                "ADDITIONAL_FUEL_FEE", "LANDING_TAX", "VSF_FEE", "ENGINE_TIME");
        assertSeededCodes("accounting_rule_filter_type", expectedCodes);
        for (String code : expectedCodes) {
            assertCodeMapsToUuid("accounting_rule_filter_type", code,
                    canonicalSeedUuid("accounting_rule_filter_type", "code", code));
        }
    }

    @Test
    void accounting_rule_filter_type_legacy_int_ids_match_legacy() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT legacy_int_id FROM accounting_rule_filter_type ORDER BY legacy_int_id")) {
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getInt(1));
            assertThat(ids)
                    .as("legacy AccountingRuleFilterTypeId values per FLSTest seed: 10, 20, 30, 40, 50, 60, 70, 80")
                    .containsExactly(10, 20, 30, 40, 50, 60, 70, 80);
        }
    }

    @Test
    void accounting_unit_type_seeded_with_4_canonical_codes() throws Exception {
        List<String> expectedCodes = List.of("MINUTES", "SECONDS", "LANDINGS", "START_OR_FLIGHT");
        assertSeededCodes("accounting_unit_type", expectedCodes);
        for (String code : expectedCodes) {
            assertCodeMapsToUuid("accounting_unit_type", code,
                    canonicalSeedUuid("accounting_unit_type", "code", code));
        }
    }

    @Test
    void accounting_unit_type_legacy_int_ids_match_legacy() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT legacy_int_id FROM accounting_unit_type ORDER BY legacy_int_id")) {
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getInt(1));
            assertThat(ids).containsExactly(10, 20, 30, 40);
        }
    }

    /** Per-club ref tables: operator creates via API; migration does NOT seed. */
    @Test
    void aircraft_reservation_type_NOT_seeded_in_migration() throws Exception {
        assertTableEmpty("aircraft_reservation_type");
    }

    @Test
    void planning_day_assignment_type_NOT_seeded_in_migration() throws Exception {
        assertTableEmpty("planning_day_assignment_type");
    }

    // ============================================================================
    // delivery_creation_test (harness)
    // ============================================================================

    @Test
    void delivery_creation_test_flight_fk_cascade() throws Exception {
        assertFkDeleteRule("delivery_creation_test", "flight_id", "CASCADE");
    }

    @Test
    void delivery_creation_test_unique_per_club_flight_partial() throws Exception {
        List<String> defs = indexDefs("delivery_creation_test");
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
                                + "WHERE table_schema='public' AND table_name='delivery_creation_test' "
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
                                + "WHERE table_schema='public' AND table_name='delivery_creation_test' "
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
                                + "WHERE table_schema='public' AND table_name='delivery_creation_test' "
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
    void delivery_creation_test_expected_matched_filter_ids_is_bigint_array_not_fk() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type, udt_name FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='delivery_creation_test' "
                                + "AND column_name='expected_matched_filter_ids'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("data_type"))
                    .as("expected_matched_filter_ids must be ARRAY (deleted filter is legitimate regression signal — NOT FK-enforced)")
                    .isEqualTo("ARRAY");
            assertThat(rs.getString("udt_name"))
                    .as("element type must be int8 (bigint[])")
                    .isEqualTo("_int8");
        }
    }

    @Test
    void delivery_creation_test_item_fk_cascade() throws Exception {
        assertFkDeleteRule("delivery_creation_test_item", "delivery_creation_test_id", "CASCADE");
    }

    // ============================================================================
    // Counter table
    // ============================================================================

    @Test
    void club_delivery_number_counter_pk_is_operating_club_id() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT a.attname FROM pg_index i "
                                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                                + "WHERE i.indrelid = 'club_delivery_number_counter'::regclass AND i.indisprimary")) {
            List<String> cols = new ArrayList<>();
            while (rs.next()) cols.add(rs.getString(1));
            assertThat(cols)
                    .as("club_delivery_number_counter PK must be operating_club_id (one row per club)")
                    .containsExactly("operating_club_id");
        }
    }

    @Test
    void club_delivery_number_counter_next_number_default_1() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT column_default FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='club_delivery_number_counter' "
                                + "AND column_name='next_number'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("1");
        }
    }

    @Test
    void club_delivery_number_counter_club_fk_cascade() throws Exception {
        assertFkDeleteRule("club_delivery_number_counter", "operating_club_id", "CASCADE");
    }

    // ============================================================================
    // Aggregate-root column comments cite ADR 0019 + the aggregate-prefix
    // ============================================================================

    @Test
    void aggregate_root_column_comments_reference_adr_0019() throws Exception {
        record CommentExpect(String table, String prefix) {}
        List<CommentExpect> expects = List.of(
                new CommentExpect("aircraft_reservation",   "arv"),
                new CommentExpect("planning_day",           "pln"),
                new CommentExpect("accounting_rule_filter", "arf"),
                new CommentExpect("delivery",               "dlv"),
                new CommentExpect("delivery_creation_test", "dct"));
        for (CommentExpect e : expects) {
            String comment = columnComment(e.table(), "id");
            assertThat(comment)
                    .as("%s.id COMMENT must reference ADR 0019 + the '%s_' prefix", e.table(), e.prefix())
                    .isNotNull()
                    .containsIgnoringCase("ADR 0019")
                    .contains(e.prefix() + "_");
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

    // ============================================================================
    // Helpers
    // ============================================================================

    // Schema-introspection helpers live in MigrationAssertions; thin local wrappers
    // delegate so existing call sites stay compact. Future migration stories should
    // call the static helpers directly.

    private List<String> checkConstraintDefs(String table) throws SQLException {
        return MigrationAssertions.checkConstraintDefs(dataSource, table);
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

    private void assertTableEmpty(String table) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT count(*) FROM " + table)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1))
                    .as("%s must NOT be seeded in V4 (per-club; operator creates via API)", table)
                    .isEqualTo(0);
        }
    }

    private static String canonicalSeedUuid(String table, String keyField, String keyValue) {
        for (JsonNode row : canonicalSeeds.get(table)) {
            JsonNode keyNode = row.get(keyField);
            if (keyNode != null && keyValue.equals(keyNode.asText())) {
                return row.get("uuid").asText();
            }
        }
        throw new IllegalStateException(
                "no canonical UUID for " + table + " " + keyField + "=" + keyValue);
    }

    /**
     * Insert a minimal club row (and return its id as text) so DML tests can
     * provoke CHECK / FK violations without depending on the prior story's
     * fixtures. Each call uses a fresh club_key so cross-test isolation holds
     * even within a savepoint-free path.
     */
    private String seedMinimalClub(Connection conn, String clubKey) throws SQLException {
        String chId = canonicalSeedUuid("country", "iso2", "CH");
        String clubStateActive = canonicalSeedUuid("club_state", "code", "ACTIVE");
        String clubId = newDeterministicUuid("club", clubKey);
        try (var s = conn.prepareStatement(
                "INSERT INTO club (id, clubname, club_key, country_id, club_state_id) "
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
            int deliveryNumber, int processStateId) throws SQLException {
        try (var s = conn.prepareStatement(
                "INSERT INTO delivery (id, operating_club_id, process_state_id, "
                        + "  delivery_number, delivered_on, recipient_lastname, recipient_firstname) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, now(), 'X', 'Y')")) {
            s.setString(1, id);
            s.setString(2, clubId);
            s.setInt(3, processStateId);
            s.setInt(4, deliveryNumber);
            s.executeUpdate();
        }
    }

    private void insertDeliveryWithBatch(Connection conn, String id, String clubId, int batchId)
            throws SQLException {
        try (var s = conn.prepareStatement(
                "INSERT INTO delivery (id, operating_club_id, process_state_id, batch_id) "
                        + "VALUES (?::uuid, ?::uuid, 10, ?)")) {
            s.setString(1, id);
            s.setString(2, clubId);
            s.setInt(3, batchId);
            s.executeUpdate();
        }
    }

    /** Deterministic non-canonical test UUID derived from {table, key} hash. */
    private static String newDeterministicUuid(String table, String key) {
        int h = (table + ":" + key).hashCode();
        long abs = Math.abs((long) h);
        return String.format("00000000-0000-7fff-8000-%012x", abs);
    }
}

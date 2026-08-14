package ch.alpenflight.server.migration;

import static ch.alpenflight.server.testsupport.MigrationAssertions.assertTableExists;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Map;
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
class FlightBaselineIntegrationTest {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;
    private static JsonNode canonicalSeeds;

    private static final List<String> S013_TABLES = List.of(
            "t_flight", "t_flight_crew",
            "t_aircraft", "t_aircraft_aircraft_state", "t_aircraft_operating_counter",
            "t_location", "t_inoutbound_point",
            "t_flight_type", "t_article",
            "t_flight_crew_type", "t_flight_process_state",
            "t_flight_cost_balance_type",
            "t_aircraft_type", "t_aircraft_state", "t_location_type");

    private static final List<String> S013_REFERENCE_TABLES = List.of(
            "t_aircraft_type", "t_aircraft_state", "t_location_type",
            "t_flight_crew_type", "t_flight_process_state",
            "t_flight_cost_balance_type");

    private static final List<String> S013_TENANT_SCOPED_TABLES = List.of(
            "t_flight", "t_flight_type", "t_article");

    @BeforeAll
    static void loadCanonicalSeeds() throws Exception {
        try (InputStream in = FlightBaselineIntegrationTest.class
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
    void all_baseline_tables_present() throws Exception {
        Set<String> actual = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
            while (rs.next()) actual.add(rs.getString(1));
        }
        assertThat(actual)
                .as("baseline must include every still-present flight/aircraft/location table")
                .containsAll(S013_TABLES);
        assertThat(actual)
                .as("flight_air_state was dropped by V13 — air state is computed (S-060)")
                .doesNotContain("t_flight_air_state");
    }

    @Test
    void all_pk_columns_are_uuid_not_null() throws Exception {
        record PkRow(String table, String column, String type, String nullable) {}
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
            stmt.setArray(1, stmt.getConnection().createArrayOf("text", S013_TABLES.toArray()));
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
                .as("every S-013 table must contribute a PK row to the join")
                .containsExactlyInAnyOrderElementsOf(S013_TABLES);
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
            stmt.setArray(1, stmt.getConnection().createArrayOf("text", S013_TABLES.toArray()));
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
    void flight_has_tow_flight_self_fk_set_null() throws Exception {
        assertFkDeleteRule("t_flight", "tow_flight_id", "SET NULL");
    }

    @Test
    void flight_aircraft_type_discriminator_is_smallint() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_flight' "
                                + "AND column_name='flight_aircraft_type_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .as("flight_aircraft_type_id must be SMALLINT (NOT a uuid FK to a lookup)")
                    .isEqualTo("smallint");
        }
    }

    @Test
    void flight_operating_club_id_not_null_fk_to_club_restrict() throws Exception {
        assertColumnNotNull("t_flight", "operating_club_id", "uuid");
        assertFkDeleteRule("t_flight", "operating_club_id", "RESTRICT");
    }


    @Test
    void flight_crew_composite_partial_unique_present() throws Exception {
        List<String> defs = indexDefs("t_flight_crew");
        assertThat(defs)
                .as("flight_crew must carry partial UNIQUE on (flight_id, person_id, flight_crew_type_id) WHERE deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique")
                            && lc.contains("flight_id") && lc.contains("person_id")
                            && lc.contains("flight_crew_type_id")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void flight_crew_flight_fk_on_delete_cascade() throws Exception {
        assertFkDeleteRule("t_flight_crew", "flight_id", "CASCADE");
    }

    @Test
    void flight_crew_person_fk_on_delete_restrict() throws Exception {
        assertFkDeleteRule("t_flight_crew", "person_id", "RESTRICT");
    }

    @Test
    void flight_crew_has_no_created_modified_audit_columns() throws Exception {
        for (String absent : List.of("created_on", "created_by_user_id",
                                     "modified_on", "modified_by_user_id")) {
            try (Connection conn = dataSource.getConnection();
                    var stmt = conn.prepareStatement(
                            "SELECT 1 FROM information_schema.columns "
                                    + "WHERE table_schema='public' AND table_name='t_flight_crew' "
                                    + "AND column_name = ?")) {
                stmt.setString(1, absent);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next())
                            .as("flight_crew must NOT carry %s (aggregate-internal; audit via Flight)", absent)
                            .isFalse();
                }
            }
        }
    }


    @Test
    void flight_type_is_tenant_scoped_uuid_club_id_not_null() throws Exception {
        assertColumnNotNull("t_flight_type", "operating_club_id", "uuid");
        assertFkDeleteRule("t_flight_type", "operating_club_id", "RESTRICT");
    }

    @Test
    void flight_type_club_code_unique_partial() throws Exception {
        List<String> defs = indexDefs("t_flight_type");
        assertThat(defs)
                .as("flight_type must carry partial UNIQUE on (operating_club_id, flight_code) WHERE not-null + not-deleted")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique")
                            && lc.contains("operating_club_id") && lc.contains("flight_code")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void flight_cost_balance_type_three_aircraft_flag_columns_not_null_default_false() throws Exception {
        for (String flag : List.of("is_for_glider", "is_for_tow", "is_for_motor")) {
            assertColumnNotNull("t_flight_cost_balance_type", flag, "boolean");
            try (Connection conn = dataSource.getConnection();
                    var stmt = conn.prepareStatement(
                            "SELECT column_default FROM information_schema.columns "
                                    + "WHERE table_schema='public' AND table_name='t_flight_cost_balance_type' "
                                    + "AND column_name=?")) {
                stmt.setString(1, flag);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1))
                            .as("flight_cost_balance_type.%s DEFAULT must be false", flag)
                            .isEqualTo("false");
                }
            }
        }
    }


    @Test
    void aircraft_is_cross_tenant_no_operating_club_id() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "t_aircraft");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='t_aircraft' "
                            + "AND column_name='operating_club_id'")) {
                assertThat(rs.next())
                        .as("aircraft must NOT carry an operating_club_id column (2026-05-16 cross-tenant amendment)")
                        .isFalse();
            }
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='t_aircraft' "
                            + "AND column_name='owner_club_id'")) {
                assertThat(rs.next()).as("aircraft.owner_club_id must exist").isTrue();
                assertThat(rs.getString(1))
                        .as("owner_club_id must be NULLABLE (private / charter / rental fleet)")
                        .isEqualTo("YES");
            }
        }
    }

    @Test
    void aircraft_immatriculation_global_unique_partial() throws Exception {
        List<String> defs = indexDefs("t_aircraft");
        assertThat(defs)
                .as("aircraft.immatriculation must be globally UNIQUE WHERE deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique") && lc.contains("immatriculation")
                            && lc.contains("deleted_on is null")
                            && !lc.contains("owner_club_id");
                });
    }

    @Test
    void aircraft_aircraft_state_surrogate_pk_and_unique_aircraft_valid_from() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT a.attname FROM pg_index i "
                                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                                + "WHERE i.indrelid = 't_aircraft_aircraft_state'::regclass AND i.indisprimary")) {
            List<String> cols = new ArrayList<>();
            while (rs.next()) cols.add(rs.getString(1));
            assertThat(cols)
                    .as("aircraft_aircraft_state PK must be surrogate id (NOT the legacy composite)")
                    .containsExactly("id");
        }
        List<String> defs = indexDefs("t_aircraft_aircraft_state");
        assertThat(defs)
                .as("UNIQUE (aircraft_id, valid_from)")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique") && lc.contains("aircraft_id")
                            && lc.contains("valid_from")
                            && !lc.contains("where ");
                });
    }

    @Test
    void aircraft_aircraft_state_partial_unique_current_state() throws Exception {
        List<String> defs = indexDefs("t_aircraft_aircraft_state");
        assertThat(defs)
                .as("partial UNIQUE (aircraft_id) WHERE valid_to IS NULL AND deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique") && lc.contains("aircraft_id")
                            && lc.contains("valid_to is null")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void aircraft_operating_counter_time_series_unique() throws Exception {
        List<String> defs = indexDefs("t_aircraft_operating_counter");
        assertThat(defs)
                .as("aircraft_operating_counter must carry UNIQUE (aircraft_id, at_date_time)")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique") && lc.contains("aircraft_id")
                            && lc.contains("at_date_time");
                });
    }

    @Test
    void aircraft_operating_counter_covering_index_with_include() throws Exception {
        List<String> defs = indexDefs("t_aircraft_operating_counter");
        assertThat(defs)
                .as("aircraft_operating_counter covering index: (aircraft_id, at_date_time DESC) INCLUDE (counter cols)")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("aircraft_id") && lc.contains("at_date_time")
                            && lc.contains("desc")
                            && lc.contains("include")
                            && lc.contains("flight_operating_counter_in_seconds")
                            && lc.contains("engine_operating_counter_in_seconds");
                });
    }

    @Test
    void aircraft_spot_link_https_check_retained_with_adr_0022_marker() throws Exception {
        try (Connection conn = dataSource.getConnection();
                var s = conn.prepareStatement(
                        "SELECT pg_get_constraintdef(c.oid), obj_description(c.oid, 'pg_constraint') "
                                + "FROM pg_constraint c WHERE c.conname = 'ck_aircraft_spot_link_https'")) {
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).as("ck_aircraft_spot_link_https must exist").isTrue();
                assertThat(rs.getString(1).toLowerCase(Locale.ROOT))
                        .contains("spot_link").contains("https");
                assertThat(rs.getString(2))
                        .as("ck_aircraft_spot_link_https must carry `ADR 0022 retained: …` COMMENT marker")
                        .isNotNull()
                        .containsIgnoringCase("ADR 0022 retained");
            }
        }
    }


    @Test
    void location_has_club_id_uuid_not_null() throws Exception {
        assertColumnNotNull("t_location", "club_id", "uuid");
        assertFkDeleteRule("t_location", "club_id", "RESTRICT");
    }

    @Test
    void location_icao_unique_partial_per_club() throws Exception {
        List<String> defs = indexDefs("t_location");
        assertThat(defs)
                .as("partial UNIQUE on (club_id, icao_code) WHERE icao_code IS NOT NULL AND deleted_on IS NULL")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("unique")
                            && lc.contains("club_id")
                            && lc.contains("icao_code")
                            && lc.contains("icao_code is not null")
                            && lc.contains("deleted_on is null");
                });
    }

    @Test
    void location_name_lower_functional_index() throws Exception {
        List<String> defs = indexDefs("t_location");
        assertThat(defs)
                .as("functional index on LOWER(location_name)")
                .anyMatch(d -> {
                    String lc = d.toLowerCase(Locale.ROOT);
                    return lc.contains("lower") && lc.contains("location_name");
                });
    }

    @Test
    void inoutbound_point_has_location_fk_on_delete_cascade() throws Exception {
        assertFkDeleteRule("t_inoutbound_point", "location_id", "CASCADE");
    }


    @Test
    void club_has_5_deferred_fk_columns_all_nullable_set_null() throws Exception {
        record Slot(String column, String fkTargetTable) {}
        List<Slot> slots = List.of(
                new Slot("homebase_id", "t_location"),
                new Slot("default_glider_flight_type_id", "t_flight_type"),
                new Slot("default_tow_flight_type_id", "t_flight_type"),
                new Slot("default_motor_flight_type_id", "t_flight_type"),
                new Slot("default_glider_with_motor_flight_type_id", "t_flight_type"));
        for (Slot s : slots) {
            assertColumnNullable("t_club", s.column(), "uuid");
            assertFkDeleteRule("t_club", s.column(), "SET NULL");
        }
    }

    @Test
    void club_default_glider_with_motor_flight_type_id_present() throws Exception {
        assertColumnNullable("t_club", "default_glider_with_motor_flight_type_id", "uuid");
    }



    @Test
    void aggregate_root_column_comments_reference_adr_0019() throws Exception {
        record CommentExpect(String table, String prefix) {}
        List<CommentExpect> expects = List.of(
                new CommentExpect("t_flight",      "flt"),
                new CommentExpect("t_aircraft",    "acf"),
                new CommentExpect("t_location",    "loc"),
                new CommentExpect("t_flight_type", "fty"),
                new CommentExpect("t_article",     "art"));
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT col_description((quote_ident(?))::regclass, "
                                + "(SELECT attnum FROM pg_attribute "
                                + " WHERE attrelid = (quote_ident(?))::regclass AND attname = 'id'))")) {
            for (CommentExpect e : expects) {
                stmt.setString(1, e.table);
                stmt.setString(2, e.table);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    String comment = rs.getString(1);
                    assertThat(comment)
                            .as("%s.id COMMENT must reference ADR 0019 + the '%s-' prefix", e.table, e.prefix)
                            .isNotNull()
                            .containsIgnoringCase("ADR 0019")
                            .contains(e.prefix + "-");
                }
            }
        }
    }

    @Test
    void non_aggregate_root_columns_do_not_carry_prefix_comments() throws Exception {
        List<String> internalTables = List.of(
                "t_flight_crew", "t_aircraft_aircraft_state",
                "t_aircraft_operating_counter", "t_inoutbound_point");
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT col_description((quote_ident(?))::regclass, "
                                + "(SELECT attnum FROM pg_attribute "
                                + " WHERE attrelid = (quote_ident(?))::regclass AND attname = 'id'))")) {
            for (String t : internalTables) {
                stmt.setString(1, t);
                stmt.setString(2, t);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    String comment = rs.getString(1);
                    if (comment != null) {
                        assertThat(comment)
                                .as("%s.id must NOT carry an aggregate-prefix External-form comment", t)
                                .doesNotContainIgnoringCase("External form:");
                    }
                }
            }
        }
    }


    @Test
    void reference_tables_have_no_audit_columns() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String t : S013_REFERENCE_TABLES) {
                for (String col : List.of("created_on", "modified_on")) {
                    try (var stmt = conn.prepareStatement(
                            "SELECT 1 FROM information_schema.columns "
                                    + "WHERE table_schema='public' AND table_name=? AND column_name=?")) {
                        stmt.setString(1, t);
                        stmt.setString(2, col);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next())
                                    .as("reference table %s must NOT carry audit column %s (operator-only via migration)",
                                            t, col)
                                    .isFalse();
                        }
                    }
                }
            }
        }
    }

    @Test
    void tenant_scoped_tables_have_audit_columns() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String t : S013_TENANT_SCOPED_TABLES) {
                for (String col : List.of("created_on", "created_by_user_id",
                                          "modified_on", "modified_by_user_id")) {
                    try (var stmt = conn.prepareStatement(
                            "SELECT data_type FROM information_schema.columns "
                                    + "WHERE table_schema='public' AND table_name=? AND column_name=?")) {
                        stmt.setString(1, t);
                        stmt.setString(2, col);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next())
                                    .as("tenant-scoped %s must carry audit column %s", t, col)
                                    .isTrue();
                        }
                    }
                }
            }
        }
    }


    @Test
    void aircraft_type_seeded_8_canonical_bitfield_values() throws Exception {
        List<String> expectedCodes = List.of(
                "UNKNOWN", "GLIDER", "GLIDER_WITH_MOTOR", "MOTOR_GLIDER",
                "MOTOR_AIRCRAFT", "MULTI_ENGINE", "JET", "HELICOPTER");
        assertSeededCodes("t_aircraft_type", expectedCodes);

        for (String code : expectedCodes) {
            String expectedUuid = canonicalSeedUuid("t_aircraft_type", "code", code);
            assertCodeMapsToUuid("t_aircraft_type", code, expectedUuid);
        }
    }

    @Test
    void aircraft_type_legacy_codes_are_bitfield_powers_of_two_or_zero() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT legacy_int_id FROM t_aircraft_type ORDER BY legacy_int_id")) {
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getInt(1));
            assertThat(ids).containsExactly(0, 1, 2, 4, 8, 16, 32, 64);
        }
    }

    @Test
    void aircraft_state_seeded_7_canonical_values() throws Exception {
        List<String> expectedCodes = List.of(
                "OK", "INFORMATION", "ATTENTION", "MALFUNCTION",
                "MAINTENANCE", "UNINSURED", "END_OF_LIFE");
        assertSeededCodes("t_aircraft_state", expectedCodes);
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT code, is_aircraft_flyable FROM t_aircraft_state ORDER BY code")) {
            Map<String, Boolean> flyable = new java.util.LinkedHashMap<>();
            while (rs.next()) flyable.put(rs.getString(1), rs.getBoolean(2));
            assertThat(flyable.get("OK")).isTrue();
            assertThat(flyable.get("INFORMATION")).isTrue();
            assertThat(flyable.get("ATTENTION")).isTrue();
            assertThat(flyable.get("MALFUNCTION")).isFalse();
            assertThat(flyable.get("MAINTENANCE")).isFalse();
            assertThat(flyable.get("UNINSURED")).isFalse();
            assertThat(flyable.get("END_OF_LIFE")).isFalse();
        }
    }

    @Test
    void location_type_seeded_6_canonical_values() throws Exception {
        assertSeededCodes("t_location_type", List.of(
                "WAYPOINT", "GRASS_RUNWAY", "EXTERNAL_FIELD",
                "GLIDER_AIRFIELD", "CONCRETE_RUNWAY", "OTHER"));
    }

    @Test
    void flight_crew_type_seeded_7_canonical_values() throws Exception {
        assertSeededCodes("t_flight_crew_type", List.of(
                "PILOT_OR_STUDENT", "CO_PILOT", "FLIGHT_INSTRUCTOR", "PASSENGER",
                "WINCH_OPERATOR", "OBSERVER", "FLIGHT_COST_INVOICE_RECIPIENT"));
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT legacy_int_id FROM t_flight_crew_type ORDER BY legacy_int_id")) {
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getInt(1));
            assertThat(ids).containsExactly(1, 2, 3, 4, 5, 6, 10);
        }
    }

    @Test
    void flight_process_state_seeded_8_canonical_values() throws Exception {
        assertSeededCodes("t_flight_process_state", List.of(
                "NOT_PROCESSED", "INVALID", "VALID", "LOCKED",
                "DELIVERY_PREPARATION_ERROR", "DELIVERY_PREPARED", "DELIVERY_BOOKED",
                "EXCLUDED_FROM_DELIVERY_PROCESS"));
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT legacy_int_id FROM t_flight_process_state ORDER BY legacy_int_id")) {
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getInt(1));
            assertThat(ids).containsExactly(0, 28, 30, 40, 45, 50, 60, 99);
        }
    }

    @Test
    void flight_air_state_table_was_dropped_by_S060() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.getMetaData().getTables(
                        null, "public", "t_flight_air_state", new String[]{"TABLE"})) {
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void flight_cost_balance_type_seeded_5_canonical_values() throws Exception {
        assertSeededCodes("t_flight_cost_balance_type", List.of(
                "PILOT_PAYS_ALL", "FIFTY_FIFTY_PILOT_COPILOT", "TOW_PILOT_PAYS_TOW",
                "NO_INSTRUCTOR_FEE", "INVOICE_TO_PERSON"));
    }


    @Test
    void every_load_bearing_fk_has_supporting_index() throws Exception {
        record Fk(String table, String column) {}
        List<Fk> required = List.of(
                new Fk("t_flight",                     "operating_club_id"),
                new Fk("t_flight",                     "aircraft_id"),
                new Fk("t_flight",                     "start_location_id"),
                new Fk("t_flight",                     "ldg_location_id"),
                new Fk("t_flight",                     "flight_type_id"),
                new Fk("t_flight",                     "tow_flight_id"),
                new Fk("t_flight_crew",                "flight_id"),
                new Fk("t_flight_crew",                "person_id"),
                new Fk("t_aircraft",                   "owner_club_id"),
                new Fk("t_aircraft",                   "homebase_id"),
                new Fk("t_aircraft",                   "aircraft_owner_person_id"),
                new Fk("t_aircraft_aircraft_state",    "aircraft_id"),
                new Fk("t_aircraft_operating_counter", "aircraft_id"),
                new Fk("t_flight_type",                "operating_club_id"),
                new Fk("t_article",                    "operating_club_id"),
                new Fk("t_inoutbound_point",           "location_id"));
        for (Fk fk : required) {
            List<String> defs = indexDefs(fk.table());
            String col = fk.column();
            boolean covered = defs.stream()
                    .anyMatch(d -> d.toLowerCase(Locale.ROOT).contains(col));
            assertThat(covered)
                    .as("load-bearing FK %s.%s must be backed by at least one supporting index", fk.table(), col)
                    .isTrue();
        }
    }


    private List<String> indexDefs(String table) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename=?")) {
            stmt.setString(1, table);
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> defs = new ArrayList<>();
                while (rs.next()) defs.add(rs.getString(1));
                return defs;
            }
        }
    }

    private void assertColumnNotNull(String table, String column, String dataType) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name=? AND column_name=?")) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).as("%s.%s must exist", table, column).isTrue();
                assertThat(rs.getString("data_type"))
                        .as("%s.%s type", table, column).isEqualTo(dataType);
                assertThat(rs.getString("is_nullable"))
                        .as("%s.%s NULL?", table, column).isEqualTo("NO");
            }
        }
    }

    private void assertColumnNullable(String table, String column, String dataType) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT data_type, is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name=? AND column_name=?")) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).as("%s.%s must exist", table, column).isTrue();
                assertThat(rs.getString("data_type"))
                        .as("%s.%s type", table, column).isEqualTo(dataType);
                assertThat(rs.getString("is_nullable"))
                        .as("%s.%s NULL?", table, column).isEqualTo("YES");
            }
        }
    }

    private void assertFkDeleteRule(String table, String column, String expectedRule) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement("""
                        SELECT
                            CASE c.confdeltype
                                WHEN 'a' THEN 'NO ACTION'
                                WHEN 'r' THEN 'RESTRICT'
                                WHEN 'c' THEN 'CASCADE'
                                WHEN 'n' THEN 'SET NULL'
                                WHEN 'd' THEN 'SET DEFAULT'
                            END AS delete_rule
                        FROM pg_constraint c
                        JOIN pg_class t       ON t.oid = c.conrelid
                        JOIN pg_namespace ns  ON ns.oid = t.relnamespace
                        JOIN pg_attribute a   ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                        WHERE c.contype = 'f'
                          AND ns.nspname = 'public'
                          AND t.relname = ?
                          AND a.attname = ?
                        """)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next())
                        .as("FK on %s.%s must exist", table, column).isTrue();
                assertThat(rs.getString(1))
                        .as("FK %s.%s ON DELETE rule", table, column)
                        .isEqualTo(expectedRule);
            }
        }
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
}

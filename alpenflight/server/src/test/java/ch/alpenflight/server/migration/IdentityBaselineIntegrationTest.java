package ch.alpenflight.server.migration;

import static org.assertj.core.api.Assertions.assertThat;

import static ch.alpenflight.server.testsupport.MigrationAssertions.assertTableExists;

import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
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
class IdentityBaselineIntegrationTest {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;
    private static JsonNode canonicalSeeds;

    @BeforeAll
    static void loadCanonicalSeeds() throws Exception {
        try (InputStream in = IdentityBaselineIntegrationTest.class
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
    void identity_baseline_tables_present() throws Exception {
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(
                "t_club", "t_club_extension", "t_club_state",
                "t_user",
                "t_person", "t_person_club",
                "t_country", "t_language",
                "t_member_state", "t_person_category",
                "t_length_unit_type", "t_elevation_unit_type", "t_counter_unit_type",
                "t_start_type",
                "t_email_template",
                "t_extension_type", "t_extension_value"
        ));
        Set<String> actual = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'public' "
                                + "AND table_type = 'BASE TABLE'")) {
            while (rs.next()) actual.add(rs.getString(1));
        }
        Set<String> frameworkTables = Set.of("flyway_schema_history");
        Set<String> expectedRequired = new LinkedHashSet<>();
        expectedRequired.addAll(expected);
        expectedRequired.addAll(frameworkTables);
        assertThat(actual)
                .as("V2 migration must create every S-012 identity + reference table + the framework "
                        + "tables; role / user_role are deliberately absent — Keycloak owns role "
                        + "assignment (S-052), a local catalogue would be parallel truth")
                .containsAll(expectedRequired);
    }

    @Test
    void all_pk_columns_are_uuid_not_null() throws Exception {
        record PkRow(String table, String column, String type, String nullable) {}
        List<PkRow> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery("""
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
                        WHERE t.table_schema = 'public'
                          AND t.table_name IN (
                            't_club','t_club_extension','t_club_state','t_user',
                            't_person','t_person_club','t_country','t_language','t_member_state','t_person_category',
                            't_length_unit_type','t_elevation_unit_type','t_counter_unit_type','t_start_type',
                            't_email_template','t_extension_value','t_extension_type'
                          )
                        """)) {
            while (rs.next()) {
                rows.add(new PkRow(rs.getString("table_name"), rs.getString("column_name"),
                        rs.getString("data_type"), rs.getString("is_nullable")));
            }
        }
        Set<String> seenTables = new LinkedHashSet<>();
        for (PkRow row : rows) seenTables.add(row.table());
        assertThat(seenTables)
                .as("every one of the 19 in-scope tables must contribute a PK row to the join")
                .containsExactlyInAnyOrder(
                        "t_club", "t_club_extension", "t_club_state", "t_user",
                        "t_person", "t_person_club", "t_country", "t_language", "t_member_state", "t_person_category",
                        "t_length_unit_type", "t_elevation_unit_type", "t_counter_unit_type", "t_start_type",
                        "t_email_template", "t_extension_value", "t_extension_type");
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
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "t_person");
        }
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery("""
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
                        """)) {
            while (rs.next()) {
                String type = rs.getString("data_type");
                assertThat(type)
                        .as("FK %s.%s must be uuid", rs.getString("table_name"), rs.getString("column_name"))
                        .isEqualTo("uuid");
            }
        }
    }

    @Test
    void user_has_keycloak_sub_uuid_partial_unique() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = 't_user' "
                            + "AND column_name = 'keycloak_sub'")) {
                assertThat(rs.next()).as("user.keycloak_sub must exist").isTrue();
                assertThat(rs.getString("data_type")).isEqualTo("uuid");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            }
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT indexdef FROM pg_indexes "
                            + "WHERE schemaname = 'public' AND tablename = 't_user' "
                            + "AND indexdef ILIKE '%keycloak_sub%'")) {
                List<String> defs = new ArrayList<>();
                while (rs.next()) defs.add(rs.getString("indexdef"));
                assertThat(defs)
                        .as("partial UNIQUE on user(keycloak_sub) WHERE keycloak_sub IS NOT NULL")
                        .anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("unique")
                                && d.toLowerCase(Locale.ROOT).contains("keycloak_sub is not null"));
            }
        }
    }

    @Test
    void person_has_no_club_id_column() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "t_person");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = 't_person' "
                            + "AND column_name = 'club_id'")) {
                assertThat(rs.next())
                        .as("person must NOT carry a club_id column (cross-tenant sacred cow)")
                        .isFalse();
            }
        }
    }

    @Test
    void person_club_has_surrogate_id_pk_and_composite_unique() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT a.attname FROM pg_index i "
                            + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                            + "WHERE i.indrelid = 't_person_club'::regclass AND i.indisprimary")) {
                List<String> pkCols = new ArrayList<>();
                while (rs.next()) pkCols.add(rs.getString(1));
                assertThat(pkCols)
                        .as("person_club PK must be the surrogate id (not the legacy composite)")
                        .containsExactly("id");
            }
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='t_person_club'")) {
                List<String> defs = new ArrayList<>();
                while (rs.next()) defs.add(rs.getString(1));
                assertThat(defs)
                        .as("composite UNIQUE on (person_id, club_id) WHERE deleted_on IS NULL")
                        .anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("unique")
                                && d.toLowerCase(Locale.ROOT).contains("person_id")
                                && d.toLowerCase(Locale.ROOT).contains("club_id")
                                && d.toLowerCase(Locale.ROOT).contains("deleted_on is null"));
            }
        }
    }

    @Test
    void aggregate_root_column_comments_reference_adr_0019() throws Exception {
        record CommentExpect(String table, String prefix) {}
        List<CommentExpect> expects = List.of(
                new CommentExpect("t_person", "psn"),
                new CommentExpect("t_club",   "clb"),
                new CommentExpect("t_user", "usr"));

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
                            .as("%s.id COMMENT must reference ADR 0019 + the '%s' prefix", e.table, e.prefix)
                            .isNotNull()
                            .containsIgnoringCase("ADR 0019")
                            .contains(e.prefix + "-");
                }
            }
        }
    }

    @Test
    void user_club_id_principal_subject_comment_present() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT col_description('t_user'::regclass, "
                                + "(SELECT attnum FROM pg_attribute "
                                + " WHERE attrelid = 't_user'::regclass AND attname = 'club_id'))")) {
            assertThat(rs.next()).isTrue();
            String comment = rs.getString(1);
            assertThat(comment)
                    .as("user.club_id comment must flag it as principal subject (NOT @TenantId)")
                    .isNotNull()
                    .containsIgnoringCase("principal");
        }
    }

    @Test
    void country_seeded_with_canonical_switzerland_uuid() throws Exception {
        String expectedSwitzerlandUuid = canonicalSeedUuid("t_country", "iso2", "CH");
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement("SELECT id::text FROM t_country WHERE iso2_code = 'CH'")) {
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).as("Switzerland must be in the seed").isTrue();
                assertThat(rs.getString(1))
                        .as("Switzerland's UUID must be bit-identical to the canonical seed")
                        .isEqualTo(expectedSwitzerlandUuid);
            }
        }
    }

    @Test
    void country_count_at_least_196() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery("SELECT count(*) FROM t_country")) {
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(196);
        }
    }

    @Test
    void start_type_seeded_5_canonical_values() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT code FROM t_start_type ORDER BY code")) {
            List<String> codes = new ArrayList<>();
            while (rs.next()) codes.add(rs.getString(1));
            assertThat(codes).containsExactlyInAnyOrder(
                    "WINCH_LAUNCH", "AEROTOW", "SELF_START", "EXTERNAL_START", "MOTOR");
        }
    }

    @Test
    void start_type_has_applicable_categories_text_array_no_check() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema='public' AND table_name='t_start_type' "
                            + "AND column_name='applicable_categories'")) {
                assertThat(rs.next()).as("applicable_categories column must exist").isTrue();
                assertThat(rs.getString("data_type"))
                        .as("Postgres reports TEXT[] columns as 'ARRAY'")
                        .isEqualTo("ARRAY");
                assertThat(rs.getString("is_nullable")).isEqualTo("NO");
            }
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                            + "WHERE conrelid = 't_start_type'::regclass AND contype = 'c'")) {
                List<String> defs = new ArrayList<>();
                while (rs.next()) defs.add(rs.getString(1));
                assertThat(defs)
                        .as("ADR 0020 — no DB CHECK on enum-value-set / SET-membership; Java enforces")
                        .noneMatch(d -> d.contains("applicable_categories"));
            }
        }
    }

    @Test
    void start_type_seeds_have_expected_applicable_categories() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT code, applicable_categories::text FROM t_start_type ORDER BY code")) {
            java.util.Map<String, String> got = new java.util.LinkedHashMap<>();
            while (rs.next()) got.put(rs.getString(1), rs.getString(2));
            assertThat(got).contains(
                    java.util.Map.entry("WINCH_LAUNCH",   "{GLIDER}"),
                    java.util.Map.entry("AEROTOW",        "{GLIDER,TOW}"),
                    java.util.Map.entry("SELF_START",     "{GLIDER}"),
                    java.util.Map.entry("EXTERNAL_START", "{GLIDER}"),
                    java.util.Map.entry("MOTOR",          "{MOTOR}"));
        }
    }

    @Test
    void club_state_seeded_3_canonical_values() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT code FROM t_club_state ORDER BY code")) {
            List<String> codes = new ArrayList<>();
            while (rs.next()) codes.add(rs.getString(1));
            assertThat(codes).containsExactlyInAnyOrder("ACTIVE", "SUSPENDED", "CLOSED");
        }
    }

    @Test
    void v2_migration_text_does_not_seed_member_state_or_person_category() throws Exception {
        Path v2 = Path.of("src/main/resources/db/migration/V2__identity_and_reference.sql");
        if (!Files.isRegularFile(v2)) {
            v2 = Path.of("alpenflight/server/src/main/resources/db/migration/V2__identity_and_reference.sql");
        }
        String body = Files.readString(v2);
        assertThat(body)
                .as("member states are per-club S-016 cutover seeds; asserted on V2's text because "
                        + "later ITs legitimately leave rows in the table (ADR 0021 pre-clean, no teardown)")
                .doesNotContainPattern("(?im)^\\s*INSERT INTO t_member_state\\b");
        assertThat(body)
                .as("person categories are per-club S-016 cutover seeds; asserted on V2's text because "
                        + "later ITs legitimately leave rows in the table (ADR 0021 pre-clean, no teardown)")
                .doesNotContainPattern("(?im)^\\s*INSERT INTO t_person_category\\b");
    }

    @Test
    void person_email_shape_checks_retained_with_adr_0022_marker() throws Exception {
        for (String name : List.of("ck_person_email_private_shape", "ck_person_email_business_shape")) {
            try (Connection conn = dataSource.getConnection();
                    var s = conn.prepareStatement(
                            "SELECT pg_get_constraintdef(c.oid), obj_description(c.oid, 'pg_constraint') "
                                    + "FROM pg_constraint c WHERE c.conname = ?")) {
                s.setString(1, name);
                try (ResultSet rs = s.executeQuery()) {
                    assertThat(rs.next()).as("retained CHECK %s must exist", name).isTrue();
                    assertThat(rs.getString(1)).containsIgnoringCase("email");
                    assertThat(rs.getString(2))
                            .as("%s must carry `ADR 0022 retained: …` COMMENT marker", name)
                            .isNotNull()
                            .containsIgnoringCase("ADR 0022 retained");
                }
            }
        }
    }

    @Test
    void country_iso2_length_pinned() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type, character_maximum_length FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_country' "
                                + "AND column_name='iso2_code'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("character");
            assertThat(rs.getInt("character_maximum_length")).isEqualTo(2);
        }
    }

    @Test
    void username_lower_functional_unique_index() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='t_user'")) {
            List<String> defs = new ArrayList<>();
            while (rs.next()) defs.add(rs.getString(1));
            assertThat(defs)
                    .as("functional UNIQUE on LOWER(username)")
                    .anyMatch(d -> {
                        String lc = d.toLowerCase(Locale.ROOT);
                        return lc.contains("unique") && lc.contains("lower") && lc.contains("username");
                    });
        }
    }

    @Test
    void email_template_not_null_club_id_overrides_only() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_email_template' "
                                + "AND column_name='club_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .as("email_template holds ONLY per-club overrides; system defaults are "
                            + "S-082 Thymeleaf files, never rows, so club_id is NOT NULL")
                    .isEqualTo("NO");
        }
    }

    @Test
    void extension_value_nullable_club_id_for_defaults() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_extension_value' "
                                + "AND column_name='club_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("YES");
        }
    }

    @Test
    void person_club_role_flags_not_null_default_false() throws Exception {
        List<String> flags = List.of(
                "is_motor_pilot", "is_tow_pilot", "is_glider_instructor",
                "is_glider_pilot", "is_glider_trainee", "is_passenger",
                "is_winch_operator", "is_motor_instructor",
                "receive_flight_reports", "receive_aircraft_reservation_notifications",
                "receive_planning_day_role_reminder", "is_active");
        try (Connection conn = dataSource.getConnection()) {
            for (String flag : flags) {
                try (var stmt = conn.prepareStatement(
                        "SELECT is_nullable, column_default, data_type FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='t_person_club' "
                                + "AND column_name = ?")) {
                    stmt.setString(1, flag);
                    try (ResultSet rs = stmt.executeQuery()) {
                        assertThat(rs.next()).as("flag %s must exist", flag).isTrue();
                        assertThat(rs.getString("data_type")).isEqualTo("boolean");
                        assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                        assertThat(rs.getString("column_default")).isEqualTo("false");
                    }
                }
            }
        }
    }

    @Test
    void audit_columns_present_on_mutable_tables() throws Exception {
        List<String> aggregateRootsPlusPersonClubMutatedOutsideItsRoot =
                List.of("t_person", "t_club", "t_user", "t_person_club");
        try (Connection conn = dataSource.getConnection()) {
            for (String t : aggregateRootsPlusPersonClubMutatedOutsideItsRoot) {
                for (String col : List.of("created_on", "created_by_user_id", "modified_on", "modified_by_user_id")) {
                    try (var stmt = conn.prepareStatement(
                            "SELECT data_type FROM information_schema.columns "
                                    + "WHERE table_schema='public' AND table_name=? AND column_name=?")) {
                        stmt.setString(1, t);
                        stmt.setString(2, col);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next())
                                    .as("table %s must carry %s", t, col)
                                    .isTrue();
                            String type = rs.getString(1);
                            if (col.endsWith("_user_id")) {
                                assertThat(type)
                                        .as("%s.%s must be uuid (no FK by design)", t, col)
                                        .isEqualTo("uuid");
                            } else {
                                assertThat(type)
                                        .as("%s.%s must be timestamptz", t, col)
                                        .isEqualTo("timestamp with time zone");
                            }
                        }
                    }
                }
            }
        }
    }

    private static String canonicalSeedUuid(String table, String keyField, String keyValue) {
        String seedKey = table.startsWith("t_") ? table.substring(2) : table;
        for (JsonNode row : canonicalSeeds.get(seedKey)) {
            if (keyValue.equals(row.get(keyField).asText())) {
                return row.get("uuid").asText();
            }
        }
        throw new IllegalStateException(
                "no canonical UUID for " + table + " " + keyField + "=" + keyValue);
    }
}

package ch.fls.server.migration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fls.server.testsupport.PostgresTestContainerLifecycle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Asserts S-012's V2__identity_and_reference migration produced the 19-table
 * identity + reference baseline with UUID-everywhere PKs, the aggregate-prefix
 * column comments, the partial-unique indexes, and the canonical reference
 * seeds.
 *
 * <p>Shares the Postgres container shape with FlywayBootstrapIntegrationTest
 * so Spring's context cache reuses the same boot.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "dockerAvailable",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class IdentityBaselineIntegrationTest {

    private static final PostgresTestContainerLifecycle POSTGRES = new PostgresTestContainerLifecycle();
    private static final boolean DOCKER_AVAILABLE = tryStartContainer();
    private static JsonNode canonicalSeeds;

    private static boolean tryStartContainer() {
        try {
            POSTGRES.start();
            return true;
        } catch (Throwable t) {
            System.err.println("""
                    [fls-server] Skipping IdentityBaselineIntegrationTest — Docker unreachable.
                      Root cause: %s
                    """.formatted(t.getMessage()));
            return false;
        }
    }

    static boolean dockerAvailable() {
        return DOCKER_AVAILABLE;
    }

    @BeforeAll
    static void loadCanonicalSeeds() throws Exception {
        try (InputStream in = IdentityBaselineIntegrationTest.class
                .getResourceAsStream("/reference-seeds-canonical-uuids.json")) {
            canonicalSeeds = new ObjectMapper().readTree(in);
        }
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
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

    /** AC1 — every domain table listed in S-012 is present. */
    @Test
    void all_19_tables_present() throws Exception {
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(
                "club", "club_extension", "club_state",
                "user", "role", "user_role",
                "person", "person_club",
                "country", "language",
                "member_state", "person_category",
                "length_unit_type", "elevation_unit_type", "counter_unit_type",
                "start_type",
                "email_template",
                "extension_type", "extension_value"
        ));
        Set<String> actual = new LinkedHashSet<>();
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'public' "
                                + "AND table_type = 'BASE TABLE'")) {
            while (rs.next()) actual.add(rs.getString(1));
        }
        assertThat(actual)
                .as("V2 migration must create all 19 identity + reference tables")
                .containsAll(expected);
    }

    /** AC2 — every PK across the 19 tables is `uuid NOT NULL`. */
    @Test
    void all_pk_columns_are_uuid_not_null() throws Exception {
        // 19 tables × 1 PK each. Single info_schema query proves every PK is uuid + not null.
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "person"); // canary: at least the identity baseline must be present
        }
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
                            'club','club_extension','club_state','user','role','user_role',
                            'person','person_club','country','language','member_state','person_category',
                            'length_unit_type','elevation_unit_type','counter_unit_type','start_type',
                            'email_template','extension_type','extension_value'
                          )
                        """)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String col = rs.getString("column_name");
                String type = rs.getString("data_type");
                String nullable = rs.getString("is_nullable");
                assertThat(type)
                        .as("PK %s.%s must be uuid (ADR 0019)", table, col)
                        .isEqualTo("uuid");
                assertThat(nullable)
                        .as("PK %s.%s must be NOT NULL", table, col)
                        .isEqualTo("NO");
            }
        }
    }

    /** AC2 — every FK column points at a uuid column (no Long/int leakage). */
    @Test
    void all_fk_columns_are_uuid() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "person");
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

    /** AC6 — user.keycloak_sub is a uuid, nullable, with a partial UNIQUE index. */
    @Test
    void user_has_keycloak_sub_uuid_partial_unique() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT data_type, is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = 'user' "
                            + "AND column_name = 'keycloak_sub'")) {
                assertThat(rs.next()).as("user.keycloak_sub must exist").isTrue();
                assertThat(rs.getString("data_type")).isEqualTo("uuid");
                assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            }
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT indexdef FROM pg_indexes "
                            + "WHERE schemaname = 'public' AND tablename = 'user' "
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

    /** Sacred-cow pin: Person has NO club_id column (cross-tenant by design). */
    @Test
    void person_has_no_club_id_column() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            assertTableExists(conn, "person");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT 1 FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = 'person' "
                            + "AND column_name = 'club_id'")) {
                assertThat(rs.next())
                        .as("person must NOT carry a club_id column (cross-tenant sacred cow)")
                        .isFalse();
            }
        }
    }

    /** AC5 — person_club reshapes legacy composite to surrogate id + composite UNIQUE. */
    @Test
    void person_club_has_surrogate_id_pk_and_composite_unique() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT a.attname FROM pg_index i "
                            + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                            + "WHERE i.indrelid = 'person_club'::regclass AND i.indisprimary")) {
                List<String> pkCols = new ArrayList<>();
                while (rs.next()) pkCols.add(rs.getString(1));
                assertThat(pkCols)
                        .as("person_club PK must be the surrogate id (not the legacy composite)")
                        .containsExactly("id");
            }
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='person_club'")) {
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

    /** Aggregate-root column comments cite ADR 0019 + the aggregate-prefix scheme. */
    @Test
    void aggregate_root_column_comments_reference_adr_0019() throws Exception {
        record CommentExpect(String table, String prefix) {}
        List<CommentExpect> expects = List.of(
                new CommentExpect("person", "psn"),
                new CommentExpect("club",   "clb"),
                new CommentExpect("user",   "usr"));

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
                            .contains(e.prefix + "_");
                }
            }
        }
    }

    /** user.club_id carries the principal-subject SQL comment (NOT a @TenantId). */
    @Test
    void user_club_id_principal_subject_comment_present() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT col_description('\"user\"'::regclass, "
                                + "(SELECT attnum FROM pg_attribute "
                                + " WHERE attrelid = '\"user\"'::regclass AND attname = 'club_id'))")) {
            assertThat(rs.next()).isTrue();
            String comment = rs.getString(1);
            assertThat(comment)
                    .as("user.club_id comment must flag it as principal subject (NOT @TenantId)")
                    .isNotNull()
                    .containsIgnoringCase("principal");
        }
    }

    /** Switzerland's UUID is the canonical-seed sacred cow — pinned in the JSON. */
    @Test
    void country_seeded_with_canonical_switzerland_uuid() throws Exception {
        String expectedSwitzerlandUuid = canonicalSeedUuid("country", "iso2", "CH");
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.prepareStatement("SELECT id::text FROM country WHERE iso2_code = 'CH'")) {
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
                ResultSet rs = conn.createStatement().executeQuery("SELECT count(*) FROM country")) {
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(196);
        }
    }

    @Test
    void start_type_seeded_5_canonical_values() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT code FROM start_type ORDER BY code")) {
            List<String> codes = new ArrayList<>();
            while (rs.next()) codes.add(rs.getString(1));
            assertThat(codes).containsExactlyInAnyOrder(
                    "WINCH_LAUNCH", "AEROTOW", "SELF_START", "EXTERNAL_START", "MOTOR");
        }
    }

    @Test
    void club_state_seeded_3_canonical_values() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT code FROM club_state ORDER BY code")) {
            List<String> codes = new ArrayList<>();
            while (rs.next()) codes.add(rs.getString(1));
            assertThat(codes).containsExactlyInAnyOrder("ACTIVE", "SUSPENDED", "CLOSED");
        }
    }

    @Test
    void role_seeded_with_canonical_codes() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT code FROM role ORDER BY code")) {
            List<String> codes = new ArrayList<>();
            while (rs.next()) codes.add(rs.getString(1));
            assertThat(codes).containsExactlyInAnyOrder(
                    "ADMIN", "FLIGHT_OPS", "INSTRUCTOR", "PILOT", "READER");
        }
    }

    @Test
    void member_state_not_seeded_in_this_migration() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT count(*) FROM member_state")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("member_state seeds are per-club; populated at S-016 cutover, not in V2")
                    .isZero();
        }
    }

    @Test
    void person_category_not_seeded_in_this_migration() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT count(*) FROM person_category")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void person_email_check_constraint_present() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conrelid = 'person'::regclass AND contype = 'c'")) {
            List<String> defs = new ArrayList<>();
            while (rs.next()) defs.add(rs.getString(1));
            assertThat(defs)
                    .as("person.email_private/business must carry a LIKE/regex sanity check")
                    .anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("email"));
        }
    }

    @Test
    void person_birthday_check_not_in_future() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conrelid = 'person'::regclass AND contype = 'c'")) {
            List<String> defs = new ArrayList<>();
            while (rs.next()) defs.add(rs.getString(1));
            assertThat(defs).anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("birthday")
                    && d.toLowerCase(Locale.ROOT).contains("current_date"));
        }
    }

    @Test
    void country_iso2_length_pinned() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT data_type, character_maximum_length FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='country' "
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
                        "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='user'")) {
            List<String> defs = new ArrayList<>();
            while (rs.next()) defs.add(rs.getString(1));
            assertThat(defs)
                    .as("functional UNIQUE on LOWER(username)")
                    .anyMatch(d -> d.toLowerCase(Locale.ROOT).contains("unique")
                            && d.toLowerCase(Locale.ROOT).contains("lower(username")
                            && d.toLowerCase(Locale.ROOT).contains("text)"));
        }
    }

    @Test
    void email_template_nullable_club_id_for_defaults() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='email_template' "
                                + "AND column_name='club_id'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1))
                    .as("email_template.club_id IS NULL means a SYSTEM_GLOBAL default")
                    .isEqualTo("YES");
        }
    }

    @Test
    void extension_value_nullable_club_id_for_defaults() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='extension_value' "
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
                                + "WHERE table_schema='public' AND table_name='person_club' "
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
        // Sample of mutable tables that must carry the audit quad.
        List<String> mutables = List.of("person", "club", "user", "person_club", "user_role");
        try (Connection conn = dataSource.getConnection()) {
            for (String t : mutables) {
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

    private static void assertTableExists(Connection conn, String tableName) throws java.sql.SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?")) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next())
                        .as("precondition: table public.%s must exist before column-shape checks", tableName)
                        .isTrue();
            }
        }
    }

    private static String canonicalSeedUuid(String table, String keyField, String keyValue) {
        for (JsonNode row : canonicalSeeds.get(table)) {
            if (keyValue.equals(row.get(keyField).asText())) {
                return row.get("uuid").asText();
            }
        }
        throw new IllegalStateException(
                "no canonical UUID for " + table + " " + keyField + "=" + keyValue);
    }
}

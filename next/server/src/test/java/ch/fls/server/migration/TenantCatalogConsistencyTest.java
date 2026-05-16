package ch.fls.server.migration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fls.server.testsupport.PostgresTestContainerLifecycle;
import ch.fls.server.testsupport.SharedPostgresContainer;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts the tenant-rules.yaml catalog is consistent with the actual schema
 * shape produced by V2__identity_and_reference: tenant-scoped tables carry a
 * club_id; cross-tenant tables don't; reference tables don't; the type pins
 * (UUID, Hibernate 7) reflect ADR 0019.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "ch.fls.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class TenantCatalogConsistencyTest {

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;
    private static Map<String, Object> tenantRules;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadTenantRules() throws Exception {
        // tenant-rules.yaml lives in a sibling Gradle module (next/database/).
        // Loaded directly from the filesystem; no build copy task needed.
        Path yamlPath = locateTenantRules();
        try (FileInputStream in = new FileInputStream(yamlPath.toFile())) {
            tenantRules = new Yaml().load(in);
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
    void tenant_rules_yaml_tenant_id_type_is_uuid() {
        assertThat(tenantRules.get("tenant_id_type"))
                .as("ADR 0019 pinned UUID; tenant column type follows")
                .isEqualTo("UUID");
    }

    @Test
    void tenant_rules_yaml_hibernate_pin_is_7x() {
        assertThat(tenantRules.get("hibernate_pin"))
                .as("ADR 0001 + 0019 — Hibernate 7.x required for UUID v7 generation")
                .isEqualTo("7.x");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenant_rules_yaml_pii_columns_present_for_person_and_user() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> person = (Map<String, Object>) overrides.get("Persons");
        Map<String, Object> user = (Map<String, Object>) overrides.get("Users");
        Map<String, Object> personClub = (Map<String, Object>) overrides.get("PersonClub");

        assertThat(person)
                .as("Persons override must enumerate pii_columns")
                .containsKey("pii_columns");
        assertThat((List<String>) person.get("pii_columns"))
                .as("PII column list on Persons must include at least the direct identifiers")
                .contains("firstname", "lastname", "birthday", "email_private", "licence_number");

        assertThat(user)
                .as("Users override must enumerate pii_columns")
                .containsKey("pii_columns");
        assertThat((List<String>) user.get("pii_columns"))
                .contains("username", "phone_number");

        assertThat(personClub).containsKey("pii_columns");
        assertThat((List<String>) personClub.get("pii_columns"))
                .as("member_number is quasi-PII (name+number reveals membership)")
                .contains("member_number");
    }

    @Test
    @SuppressWarnings("unchecked")
    void member_state_reclassified_to_tenant_scoped() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> memberStates = (Map<String, Object>) overrides.get("MemberStates");
        assertThat(memberStates.get("kind"))
                .as("ADR 0018 + legacy ClubId NOT NULL → MemberStates is tenant-scoped, internal to Club")
                .isEqualTo("tenant-scoped");
    }

    @Test
    @SuppressWarnings("unchecked")
    void person_category_reclassified_to_tenant_scoped() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> personCategories = (Map<String, Object>) overrides.get("PersonCategories");
        assertThat(personCategories.get("kind")).isEqualTo("tenant-scoped");
    }

    @Test
    void every_tenant_scoped_table_has_club_id_uuid_not_null() throws Exception {
        // Names of tables that S-012 actually creates, classified as tenant-scoped here.
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

    private static void assertTableExists(Connection conn, String tableName) throws java.sql.SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?")) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next())
                        .as("precondition: table public.%s must exist before classification checks", tableName)
                        .isTrue();
            }
        }
    }

    /**
     * tenant-rules.yaml lives in a sibling Gradle module. Resolution strategy:
     * walk up from the working dir until we find a directory containing both
     * the {@code next/server/} and {@code next/database/} folders, then drop
     * the yaml path off that base.
     */
    private static Path locateTenantRules() {
        Path cwd = Path.of("").toAbsolutePath();
        Path probe = cwd;
        while (probe != null) {
            Path candidate = probe.resolve("next/database/tenant-rules.yaml");
            if (Files.exists(candidate)) return candidate;
            // also accept relative when running from next/server/
            Path siblingCandidate = probe.resolve("../database/tenant-rules.yaml").normalize();
            if (Files.exists(siblingCandidate)) return siblingCandidate;
            probe = probe.getParent();
        }
        throw new IllegalStateException(
                "tenant-rules.yaml not found under any ancestor of " + cwd
                        + " — expected at <repo>/next/database/tenant-rules.yaml");
    }
}

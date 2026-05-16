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
 * Asserts the live schema produced by V2__identity_and_reference matches the
 * tenant-scope catalog's classification: tenant-scoped tables carry a
 * {@code club_id NOT NULL}, cross-tenant tables don't, reference tables
 * don't. Pure-YAML assertions over {@code next/database/tenant-rules.yaml}
 * live in {@link TenantCatalogYamlTest} (plain JUnit; no Docker dep).
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
}

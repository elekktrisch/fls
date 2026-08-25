package ch.alpenflight.server.migration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoSeatPoolMigrationIT extends PostgresIntegrationTest {

    private static final int SEEDED_SEAT_POOL_SIZE = 10;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void demo_seat_is_a_platform_table_without_a_tenant_column() {
        List<String> tenantColumns = jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 't_demo_seat'
                AND column_name IN ('club_id', 'operating_club_id', 'tenant_club_id')
                """,
                String.class);
        assertThat(tenantColumns)
                .as("t_demo_seat maps a seat to its club before a tenant is resolved, "
                        + "so club_id is a plain FK and no @TenantId discriminator exists")
                .containsExactly("club_id");

        String tenantColumnComment = jdbc.queryForObject(
                "SELECT obj_description('t_demo_seat'::regclass, 'pg_class')", String.class);
        assertThat(tenantColumnComment)
                .as("the platform declaration follows the t_job_run convention")
                .contains("Platform")
                .contains("@TenantId");
    }

    @Test
    void demo_seat_club_id_is_a_restricting_foreign_key_to_club() {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_name = 't_demo_seat'
                AND constraint_name = 'fk_demo_seat_club_id'
                AND constraint_type = 'FOREIGN KEY'
                """,
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void demo_seat_carries_the_columns_the_lease_needs() {
        Map<String, Object> columns = jdbc.queryForList(
                """
                SELECT column_name, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 't_demo_seat'
                """).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> row.get("column_name").toString(),
                        row -> row.get("is_nullable").toString()));

        assertThat(columns).containsEntry("seat_number", "NO");
        assertThat(columns).containsEntry("club_id", "NO");
        assertThat(columns).containsEntry("keycloak_username", "NO");
        assertThat(columns).containsEntry("lease_state", "NO");
        assertThat(columns)
                .as("a free seat holds no address and no expiry instant")
                .containsEntry("lease_holder_key", "YES")
                .containsEntry("lease_expires_at", "YES");
        assertThat(columns)
                .as("the optimistic lock cell makes the claim of a free seat atomic")
                .containsEntry("version", "NO");
    }

    @Test
    void seat_identity_is_unique_but_the_address_cap_stays_a_java_policy() {
        List<String> uniqueIndexes = jdbc.queryForList(
                """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 't_demo_seat'
                AND indexdef LIKE '%UNIQUE%'
                ORDER BY indexname
                """,
                String.class);
        assertThat(uniqueIndexes).contains(
                "ux_demo_seat_club_id",
                "ux_demo_seat_keycloak_username",
                "ux_demo_seat_seat_number");

        List<String> holderKeyIndexes = jdbc.queryForList(
                """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 't_demo_seat'
                AND indexdef LIKE '%UNIQUE%' AND indexdef LIKE '%lease_holder_key%'
                """,
                String.class);
        assertThat(holderKeyIndexes)
                .as("demo.max-live-seats-per-address is a configurable Java rule; a UNIQUE "
                        + "index would pin the cap at 1 and break the end-to-end profile")
                .isEmpty();
    }

    @Test
    void ten_seat_clubs_live_under_the_sandbox_deployment() {
        List<Map<String, Object>> clubs = jdbc.queryForList(
                """
                SELECT c.club_key, c.deployment_id::text AS deployment_id
                FROM t_club c
                JOIN t_demo_seat s ON s.club_id = c.id
                ORDER BY s.seat_number
                """);
        assertThat(clubs).hasSize(SEEDED_SEAT_POOL_SIZE);
        assertThat(clubs).allSatisfy(club -> assertThat(club.get("deployment_id"))
                .isEqualTo(Deployment.SANDBOX_ID.toString()));
        assertThat(clubs.stream().map(club -> club.get("club_key").toString()))
                .containsExactly("DEMO01", "DEMO02", "DEMO03", "DEMO04", "DEMO05",
                        "DEMO06", "DEMO07", "DEMO08", "DEMO09", "DEMO10");
    }

    @Test
    void every_seat_starts_free_and_names_its_keycloak_user() {
        List<Map<String, Object>> seats = jdbc.queryForList(
                """
                SELECT seat_number, keycloak_username, lease_state,
                       lease_holder_key, lease_expires_at
                FROM t_demo_seat ORDER BY seat_number
                """);
        assertThat(seats).hasSize(SEEDED_SEAT_POOL_SIZE);
        assertThat(seats.stream().map(seat -> seat.get("keycloak_username").toString()))
                .containsExactly("demo1", "demo2", "demo3", "demo4", "demo5",
                        "demo6", "demo7", "demo8", "demo9", "demo10");
        assertThat(seats).allSatisfy(seat -> {
            assertThat(seat.get("lease_state")).isEqualTo("FREE");
            assertThat(seat.get("lease_holder_key")).isNull();
            assertThat(seat.get("lease_expires_at")).isNull();
        });
    }

    @Test
    void no_club_outside_the_seat_pool_moved_to_the_sandbox_deployment() {
        List<String> strayClubKeys = jdbc.queryForList(
                """
                SELECT c.club_key FROM t_club c
                WHERE c.deployment_id = ?::uuid
                AND NOT EXISTS (SELECT 1 FROM t_demo_seat s WHERE s.club_id = c.id)
                """,
                String.class, Deployment.SANDBOX_ID.toString());
        assertThat(strayClubKeys)
                .as("the sandbox Deployment holds the seat clubs and nothing else")
                .isEmpty();
    }
}

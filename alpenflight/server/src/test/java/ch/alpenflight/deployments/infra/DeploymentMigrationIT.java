package ch.alpenflight.deployments.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies V14 migration outcomes against the shared Postgres testcontainer:
 *
 * <ul>
 *   <li>Sandbox Deployment seeded at {@link Deployment#SANDBOX_ID}.</li>
 *   <li>Operator Deployment seeded at {@link Deployment#OPERATOR_ID}; owner_sub
 *       comes from the {@code alpenflight.operator.keycloak_sub} placeholder.</li>
 *   <li>V5 seed Club ({@code 019e30c3-2c00-7001-8000-000000000001})
 *       backfilled to the operator Deployment.</li>
 *   <li>Partial UNIQUE {@code ux_deployment_owner_active} present in
 *       {@code pg_indexes} with the expected predicate.</li>
 *   <li>Performance indexes {@code ix_deployment_lifecycle} +
 *       {@code ix_club_deployment_id} present.</li>
 * </ul>
 */
class DeploymentMigrationIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sandbox_deployment_seeded() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT lifecycle_state, plan, owner_keycloak_sub FROM t_deployment WHERE id = ?::uuid",
                Deployment.SANDBOX_ID.toString());
        assertThat(row.get("lifecycle_state")).isEqualTo("SANDBOX");
        assertThat(row.get("plan")).isEqualTo("FREE");
        assertThat(row.get("owner_keycloak_sub").toString())
                .isEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    void operator_deployment_seeded_in_active_state() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT lifecycle_state, plan FROM t_deployment WHERE id = ?::uuid",
                Deployment.OPERATOR_ID.toString());
        assertThat(row.get("lifecycle_state")).isEqualTo("ACTIVE");
        assertThat(row.get("plan")).isEqualTo("ACTIVE");
    }

    @Test
    void v5_seed_club_backfilled_to_operator_deployment() {
        String deploymentId = jdbc.queryForObject(
                "SELECT deployment_id::text FROM t_club WHERE id = ?::uuid",
                String.class, "019e30c3-2c00-7001-8000-000000000001");
        assertThat(deploymentId).isEqualTo(Deployment.OPERATOR_ID.toString());
    }

    @Test
    void partial_unique_index_present_on_owner_active() {
        List<Map<String, Object>> indexes = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?",
                "ux_deployment_owner_active");
        assertThat(indexes).hasSize(1);
        String def = indexes.get(0).get("indexdef").toString();
        assertThat(def)
                .contains("UNIQUE")
                .contains("owner_keycloak_sub")
                .contains("'TRIAL'")
                .contains("'ACTIVE'")
                .contains("'PAST_DUE'")
                .contains("'CANCELLED'");
        assertThat(def).doesNotContain("'SANDBOX'").doesNotContain("'DELETING'");
    }

    @Test
    void performance_indexes_present() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?",
                Integer.class, "ix_deployment_lifecycle")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = ?",
                Integer.class, "ix_club_deployment_id")).isEqualTo(1);
    }

    @Test
    void club_deployment_id_fk_constraint_present() {
        Integer count = jdbc.queryForObject("""
                        SELECT count(*) FROM information_schema.table_constraints
                        WHERE table_name = 'club'
                        AND constraint_name = 'fk_club_deployment_id'
                        AND constraint_type = 'FOREIGN KEY'
                        """, Integer.class);
        assertThat(count).isEqualTo(1);
    }
}

package ch.alpenflight.deployments.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies {@link DeploymentContext} cross-Club iteration:
 *
 * <ul>
 *   <li>{@code forEachClub} fires the callback once per Club under the
 *       given Deployment.</li>
 *   <li>The callback observes per-Club tenant context — Hibernate's
 *       {@code @TenantId} filter switches per invocation.</li>
 *   <li>The tenant carrier is restored after iteration (no leakage to
 *       the next caller).</li>
 *   <li>{@code findDeployment} narrows by lifecycle state.</li>
 * </ul>
 */
class DeploymentContextIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-0000000000d1");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000000d2");

    @Autowired
    private DeploymentContext deploymentContext;

    @Autowired
    private DeploymentRepository deployments;

    @Autowired
    private JdbcTemplate jdbc;

    private final Clock clock = Clock.systemUTC();
    private UUID activeDeploymentId;

    @BeforeEach
    void seed() {
        // The previous test re-pointed CLUB_A/B at an IT-owned Deployment;
        // ON DELETE RESTRICT on club.deployment_id blocks the cleanup
        // DELETE unless we re-point them back first.
        jdbc.update("UPDATE club SET deployment_id = '00000000-0000-0000-0000-000000000002'::uuid "
                + "WHERE deployment_id IN (SELECT id FROM deployment WHERE name LIKE 'IT_DC_%')");
        jdbc.update("DELETE FROM deployment WHERE name LIKE 'IT_DC_%'");
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, "IT_DC_", "IT_DC_").seed();

        UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000d100");
        Deployment trial = Deployment.startTrial(clock, "IT_DC_active", owner);
        trial.activateSubscription("cus", "sub", clock);
        Deployment saved = deployments.save(trial);
        activeDeploymentId = saved.getId();

        // Re-point the two seed Clubs to this Deployment so forEachClub sees them.
        jdbc.update("UPDATE club SET deployment_id = ?::uuid WHERE id IN (?::uuid, ?::uuid)",
                activeDeploymentId.toString(),
                CLUB_A.toString(), CLUB_B.toString());
    }

    @Test
    void forEachClub_fires_once_per_club_under_their_tenant_scope() {
        List<UUID> observedTenants = new ArrayList<>();

        deploymentContext.forEachClub(activeDeploymentId, club -> {
            observedTenants.add(TenantContextCarrier.current().orElse(null));
        });

        assertThat(observedTenants).containsExactlyInAnyOrder(CLUB_A, CLUB_B);
    }

    @Test
    void forEachClub_restores_tenant_after_iteration() {
        assertThat(TenantContextCarrier.current()).isEmpty();
        deploymentContext.forEachClub(activeDeploymentId, club -> {});
        assertThat(TenantContextCarrier.current()).isEmpty();
    }

    @Test
    void findDeployment_filters_by_state() {
        List<Deployment> activeOnly = deploymentContext.findDeployment(LifecycleState.ACTIVE);
        assertThat(activeOnly).anyMatch(d -> activeDeploymentId.equals(d.getId()));

        List<Deployment> trialOnly = deploymentContext.findDeployment(LifecycleState.TRIAL);
        assertThat(trialOnly).noneMatch(d -> activeDeploymentId.equals(d.getId()));
    }

    @Test
    void findDeployment_empty_filter_returns_empty_list() {
        assertThat(deploymentContext.findDeployment()).isEmpty();
    }
}

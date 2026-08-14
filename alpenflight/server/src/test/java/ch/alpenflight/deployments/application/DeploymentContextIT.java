package ch.alpenflight.deployments.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
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

class DeploymentContextIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_DC_";
    private static final String KEY_PREFIX = "IT_DC_";

    @Autowired
    private DeploymentContext deploymentContext;

    @Autowired
    private DeploymentRepository deployments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClubRepository clubs;

    @Autowired
    private CountryRepository countries;

    @Autowired
    private ClubStateRepository clubStates;

    private final Clock clock = Clock.systemUTC();
    private UUID clubA;
    private UUID clubB;
    private UUID activeDeploymentId;

    @BeforeEach
    void seed() {
        jdbc.update("UPDATE t_club SET deployment_id = '00000000-0000-0000-0000-000000000002'::uuid "
                + "WHERE deployment_id IN (SELECT id FROM t_deployment WHERE name LIKE 'IT_DC_%')");
        jdbc.update("DELETE FROM t_deployment WHERE name LIKE 'IT_DC_%'");
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();

        UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000d100");
        Deployment trial = Deployment.startTrial(clock, "IT_DC_active", owner);
        trial.activateSubscription("cus", "sub", clock);
        Deployment saved = deployments.save(trial);
        activeDeploymentId = saved.getId();

        jdbc.update("UPDATE t_club SET deployment_id = ?::uuid WHERE id IN (?::uuid, ?::uuid)",
                activeDeploymentId.toString(),
                clubA.toString(), clubB.toString());
    }

    @Test
    void forEachClub_fires_once_per_club_under_their_tenant_scope() {
        List<UUID> observedTenants = new ArrayList<>();

        deploymentContext.forEachClub(activeDeploymentId, club -> {
            observedTenants.add(TenantContextCarrier.current().orElse(null));
        });

        assertThat(observedTenants).containsExactlyInAnyOrder(clubA, clubB);
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

package ch.alpenflight.deployments.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class DeploymentRepositoryIT extends PostgresIntegrationTest {

    private static final String IT_ONLY_NAME_PREFIX = "IT_DEPRO_";

    @Autowired
    private DeploymentRepository deployments;

    @Autowired
    private JdbcTemplate jdbc;

    private final Clock clock = Clock.systemUTC();

    @BeforeEach
    void deleteRowsFromPriorRunsTaggedWithTheItOnlyNamePrefix() {
        jdbc.update("DELETE FROM t_deployment WHERE name LIKE ?", IT_ONLY_NAME_PREFIX + "%");
    }

    private void insertDirectlySoTheUniqueViolationSurfacesAtInsertTimeInsteadOfAtFlush(
            String name, UUID owner, String lifecycleState, String plan) {
        jdbc.update("""
                        INSERT INTO t_deployment (id, name, owner_keycloak_sub,
                                                lifecycle_state, plan)
                        VALUES (?::uuid, ?, ?::uuid, ?, ?)
                        """,
                UUID.randomUUID().toString(), name, owner.toString(), lifecycleState, plan);
    }

    @Test
    void save_then_findById_round_trips() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000a001");
        Deployment saved = deployments.save(
                Deployment.startTrial(clock, "IT_DEPRO_round_trip", owner));

        UUID id = saved.getId();
        assertThat(id).isNotNull();

        Deployment loaded = deployments.findById(id).orElseThrow();
        assertThat(loaded.getLifecycleState()).isEqualTo(LifecycleState.TRIAL);
        assertThat(loaded.getOwnerKeycloakSub()).isEqualTo(owner);
        assertThat(loaded.getTrialStartedAt()).isNotNull();
    }

    @Test
    void partial_unique_blocks_second_trial_for_same_owner() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000a002");
        deployments.save(Deployment.startTrial(clock, "IT_DEPRO_first", owner));

        assertThatThrownBy(() ->
                insertDirectlySoTheUniqueViolationSurfacesAtInsertTimeInsteadOfAtFlush(
                        "IT_DEPRO_second", owner, "TRIAL", "ACTIVE"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void partial_unique_excludes_deleting_and_sandbox_states() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000a003");

        insertDirectlySoTheUniqueViolationSurfacesAtInsertTimeInsteadOfAtFlush(
                "IT_DEPRO_deleting", owner, "DELETING", "FREE");

        Deployment fresh = deployments.save(
                Deployment.startTrial(clock, "IT_DEPRO_fresh_after_delete", owner));
        assertThat(fresh.getLifecycleState())
                .as("the partial UNIQUE predicate excludes DELETING, so the same owner may "
                        + "provision a fresh TRIAL")
                .isEqualTo(LifecycleState.TRIAL);
    }

    @Test
    void findByLifecycleStateIn_filters_to_requested_states() {
        UUID activeOwner = UUID.fromString("00000000-0000-0000-0000-00000000a004");
        Deployment active = deployments.save(
                Deployment.startTrial(clock, "IT_DEPRO_active", activeOwner));
        active.activateSubscription("cus", "sub", clock);
        deployments.save(active);

        UUID trialOwner = UUID.fromString("00000000-0000-0000-0000-00000000a005");
        deployments.save(Deployment.startTrial(clock, "IT_DEPRO_trial", trialOwner));

        List<Deployment> activeOnly = deployments.findByLifecycleStateIn(
                List.of(LifecycleState.ACTIVE));
        assertThat(activeOnly).extracting(Deployment::getName).contains("IT_DEPRO_active");
        assertThat(activeOnly).extracting(Deployment::getName).doesNotContain("IT_DEPRO_trial");

        List<Deployment> bothStates = deployments.findByLifecycleStateIn(
                List.of(LifecycleState.ACTIVE, LifecycleState.TRIAL));
        assertThat(bothStates).extracting(Deployment::getName)
                .contains("IT_DEPRO_active", "IT_DEPRO_trial");
    }
}

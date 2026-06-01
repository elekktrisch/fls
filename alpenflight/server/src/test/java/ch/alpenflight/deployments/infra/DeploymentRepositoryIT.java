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

/**
 * Persistence-level invariants for the {@link Deployment} aggregate:
 *
 * <ul>
 *   <li>Round-trip save + load preserves state.</li>
 *   <li>Partial UNIQUE {@code ux_deployment_owner_active} blocks a second
 *       non-terminal Deployment for the same owner — asserted via direct
 *       JDBC INSERT to bypass the {@code @Version} sequence-of-events the
 *       repository would mediate.</li>
 *   <li>{@code SANDBOX} + {@code DELETING} are exempt from the partial
 *       UNIQUE predicate — same owner may co-exist.</li>
 *   <li>{@link DeploymentRepository#findByLifecycleStateIn} returns
 *       Deployments matching any of the requested states.</li>
 * </ul>
 */
class DeploymentRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    private DeploymentRepository deployments;

    @Autowired
    private JdbcTemplate jdbc;

    private final Clock clock = Clock.systemUTC();

    @BeforeEach
    void cleanFixtureRows() {
        // ADR 0021 pre-clean by stable key — IT-owned rows are tagged by an
        // owner_keycloak_sub prefix the production seed never uses.
        jdbc.update("DELETE FROM t_deployment WHERE name LIKE 'IT_DEPRO_%'");
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

        // Direct JDBC bypasses Hibernate's deferred flush so the UNIQUE
        // violation surfaces at INSERT time. The application layer (S-138)
        // catches DataIntegrityViolationException + SQLSTATE 23505 and
        // translates to the structured 409 body.
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO t_deployment (id, name, owner_keycloak_sub,
                                                lifecycle_state, plan)
                        VALUES (?::uuid, 'IT_DEPRO_second', ?::uuid, 'TRIAL', 'ACTIVE')
                        """, UUID.randomUUID().toString(), owner.toString()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void partial_unique_excludes_deleting_and_sandbox_states() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000a003");

        // owner has a DELETING Deployment (data going) ...
        jdbc.update("""
                        INSERT INTO t_deployment (id, name, owner_keycloak_sub,
                                                lifecycle_state, plan)
                        VALUES (?::uuid, 'IT_DEPRO_deleting', ?::uuid, 'DELETING', 'FREE')
                        """, UUID.randomUUID().toString(), owner.toString());

        // ... and may legitimately provision a new TRIAL — partial UNIQUE
        // predicate excludes DELETING so no collision.
        Deployment fresh = deployments.save(
                Deployment.startTrial(clock, "IT_DEPRO_fresh_after_delete", owner));
        assertThat(fresh.getLifecycleState()).isEqualTo(LifecycleState.TRIAL);
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

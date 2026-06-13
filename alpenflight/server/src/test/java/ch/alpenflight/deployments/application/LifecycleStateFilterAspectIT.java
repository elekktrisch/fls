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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Verifies that {@link LifecycleStateFilterAspect} dispatches the
 * annotated method body across Deployments matching the filter, and
 * skips off-list Deployments.
 *
 * <p>Drives a test-only bean that carries {@link LifecycleStateFilter} —
 * production usage pairs with {@link org.springframework.scheduling.annotation.Scheduled},
 * but the aspect's pointcut is the annotation alone (the ArchUnit rule
 * enforces the pairing at build time).
 */
@Import(LifecycleStateFilterAspectIT.TestJobConfig.class)
class LifecycleStateFilterAspectIT extends PostgresIntegrationTest {

    @Autowired
    private DeploymentRepository deployments;

    @Autowired
    private TestJob testJob;

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

    @BeforeEach
    void seed() {
        jdbc.update("UPDATE t_club SET deployment_id = '00000000-0000-0000-0000-000000000002'::uuid "
                + "WHERE deployment_id IN (SELECT id FROM t_deployment WHERE name LIKE 'IT_AS_%')");
        jdbc.update("DELETE FROM t_deployment WHERE name LIKE 'IT_AS_%'");
        testJob.reset();
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_AS_", "IT_AS_");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();

        UUID activeOwner = UUID.fromString("00000000-0000-0000-0000-00000000e100");
        Deployment active = Deployment.startTrial(clock, "IT_AS_active", activeOwner);
        active.activateSubscription("cus", "sub", clock);
        UUID activeId = deployments.save(active).getId();
        jdbc.update("UPDATE t_club SET deployment_id = ?::uuid WHERE id IN (?::uuid, ?::uuid)",
                activeId.toString(), clubA.toString(), clubB.toString());

        UUID trialOwner = UUID.fromString("00000000-0000-0000-0000-00000000e200");
        deployments.save(Deployment.startTrial(clock, "IT_AS_trial", trialOwner));
    }

    @Test
    void aspect_invokes_body_once_per_club_under_active_deployments() {
        testJob.runActiveOnly();

        // The aspect fires the body once per Club under every ACTIVE
        // Deployment — the operator Deployment plus the IT-owned ones
        // contribute. Assert presence of the two IT Clubs (the aspect's
        // tenant-switching behavior) rather than an exact count.
        assertThat(testJob.observedTenants()).contains(clubA, clubB);
    }

    @Test
    void aspect_with_empty_filter_skips_body() {
        testJob.runEmptyFilter();
        assertThat(testJob.emptyInvocationCount()).isZero();
    }

    @TestConfiguration
    static class TestJobConfig {
        @Bean
        TestJob testJob() {
            return new TestJob();
        }
    }

    /**
     * Aspect-wrapped beans run through a CGLIB proxy; field access on the
     * proxy returns the un-initialised subclass field rather than the
     * real target's. Counters are exposed through method getters so the
     * test reads them via the same dispatch path the aspect wraps.
     */
    static class TestJob {
        private final AtomicInteger activeInvocations = new AtomicInteger();
        private final AtomicInteger emptyInvocations = new AtomicInteger();
        private final List<UUID> observedTenants = new ArrayList<>();

        void reset() {
            activeInvocations.set(0);
            emptyInvocations.set(0);
            observedTenants.clear();
        }

        int activeInvocationCount() {
            return activeInvocations.get();
        }

        int emptyInvocationCount() {
            return emptyInvocations.get();
        }

        synchronized List<UUID> observedTenants() {
            return List.copyOf(observedTenants);
        }

        // @Scheduled here is metadata for the aspect's pointcut only — the
        // test never enables Spring scheduling, so the runner doesn't fire
        // these methods. Tests invoke them directly + the @Around advice
        // applies. The far-future cron expression keeps Spring's
        // schedule-parser quiet if the harness ever enables scheduling.
        @Scheduled(cron = "0 0 0 1 1 ?")
        @LifecycleStateFilter({LifecycleState.ACTIVE})
        public synchronized void runActiveOnly() {
            activeInvocations.incrementAndGet();
            TenantContextCarrier.current().ifPresent(observedTenants::add);
        }

        @Scheduled(cron = "0 0 0 1 1 ?")
        @LifecycleStateFilter({})
        public void runEmptyFilter() {
            emptyInvocations.incrementAndGet();
        }
    }
}

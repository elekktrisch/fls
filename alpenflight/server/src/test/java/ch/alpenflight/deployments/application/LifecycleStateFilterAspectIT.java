package ch.alpenflight.deployments.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Clock;
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
import org.springframework.stereotype.Component;

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

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-0000000000e1");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000000e2");

    @Autowired
    private DeploymentRepository deployments;

    @Autowired
    private TestJob testJob;

    @Autowired
    private JdbcTemplate jdbc;

    private final Clock clock = Clock.systemUTC();

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM deployment WHERE name LIKE 'IT_AS_%'");
        testJob.reset();
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, "IT_AS_", "IT_AS_").seed();

        UUID activeOwner = UUID.fromString("00000000-0000-0000-0000-00000000e100");
        Deployment active = Deployment.startTrial(clock, "IT_AS_active", activeOwner);
        active.activateSubscription("cus", "sub", clock);
        UUID activeId = deployments.save(active).getId();
        jdbc.update("UPDATE club SET deployment_id = ?::uuid WHERE id IN (?::uuid, ?::uuid)",
                activeId.toString(), CLUB_A.toString(), CLUB_B.toString());

        UUID trialOwner = UUID.fromString("00000000-0000-0000-0000-00000000e200");
        deployments.save(Deployment.startTrial(clock, "IT_AS_trial", trialOwner));
    }

    @Test
    void aspect_invokes_body_once_per_club_under_active_deployments() {
        testJob.runActiveOnly();

        // Only the ACTIVE Deployment has Clubs A + B; aspect fires the body
        // once per Club under that Deployment. The TRIAL Deployment has no
        // Clubs assigned in this fixture.
        assertThat(testJob.activeInvocations.get()).isEqualTo(2);
    }

    @Test
    void aspect_with_empty_filter_skips_body() {
        testJob.runEmptyFilter();
        assertThat(testJob.emptyInvocations.get()).isZero();
    }

    @TestConfiguration
    static class TestJobConfig {
        @Bean
        TestJob testJob() {
            return new TestJob();
        }
    }

    @Component
    static class TestJob {
        final AtomicInteger activeInvocations = new AtomicInteger();
        final AtomicInteger emptyInvocations = new AtomicInteger();

        void reset() {
            activeInvocations.set(0);
            emptyInvocations.set(0);
        }

        // @Scheduled here is metadata for the aspect's pointcut only — the
        // test never enables Spring scheduling, so the runner doesn't fire
        // these methods. Tests invoke them directly + the @Around advice
        // applies. The far-future cron expression keeps Spring's
        // schedule-parser quiet if the harness ever enables scheduling.
        @Scheduled(cron = "0 0 0 1 1 ?")
        @LifecycleStateFilter({LifecycleState.ACTIVE})
        public void runActiveOnly() {
            activeInvocations.incrementAndGet();
        }

        @Scheduled(cron = "0 0 0 1 1 ?")
        @LifecycleStateFilter({})
        public void runEmptyFilter() {
            emptyInvocations.incrementAndGet();
        }
    }
}

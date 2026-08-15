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
        repointClubsToTheOperatorDeploymentSoTheRestrictedDeleteCanRun();
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

    private void repointClubsToTheOperatorDeploymentSoTheRestrictedDeleteCanRun() {
        jdbc.update("UPDATE t_club SET deployment_id = ?::uuid "
                        + "WHERE deployment_id IN (SELECT id FROM t_deployment WHERE name LIKE 'IT_AS_%')",
                Deployment.OPERATOR_ID.toString());
    }

    @Test
    void aspect_invokes_body_once_per_club_under_active_deployments() {
        testJob.runActiveOnly();

        assertThat(testJob.observedTenants())
                .as("every ACTIVE Deployment contributes, so assert the two IT clubs are present "
                        + "rather than pinning an exact count")
                .contains(clubA, clubB);
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

    static class TestJob {
        private static final String CRON_THAT_NEVER_FIRES_BECAUSE_TESTS_CALL_THESE_DIRECTLY =
                "0 0 0 1 1 ?";

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

        @Scheduled(cron = CRON_THAT_NEVER_FIRES_BECAUSE_TESTS_CALL_THESE_DIRECTLY)
        @LifecycleStateFilter({LifecycleState.ACTIVE})
        public synchronized void runActiveOnly() {
            activeInvocations.incrementAndGet();
            TenantContextCarrier.current().ifPresent(observedTenants::add);
        }

        @Scheduled(cron = CRON_THAT_NEVER_FIRES_BECAUSE_TESTS_CALL_THESE_DIRECTLY)
        @LifecycleStateFilter({})
        public void runEmptyFilter() {
            emptyInvocations.incrementAndGet();
        }
    }
}

package ch.alpenflight.platform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@Import(MeasuredJobAspectIT.TestJobConfig.class)
class MeasuredJobAspectIT extends PostgresIntegrationTest {

    static final String COMPLETING_JOB = "it-measured-completing";
    static final String THROWING_JOB = "it-measured-throwing";

    @Autowired
    private CompletingJob completingJob;

    @Autowired
    private ThrowingJob throwingJob;

    @Autowired
    private JobRunRepository jobRuns;

    @Autowired
    private MeterRegistry meters;

    @Test
    void completing_job_persists_completed_run_with_timing() {
        completingJob.runOnce();

        JobRun latest = jobRuns.findLatestByJobName(COMPLETING_JOB).orElseThrow();
        assertThat(latest.getStatus()).isEqualTo(JobRun.Status.COMPLETED);
        assertThat(latest.getStartedAt()).isNotNull();
        assertThat(latest.getFinishedAt()).isNotNull();
        assertThat(latest.getFinishedAt()).isAfterOrEqualTo(latest.getStartedAt());
        assertThat(latest.getSummary()).isEqualTo("processed=3");
        assertThat(latest.getError()).isNull();

        Timer timer = meters.find("fls_job_duration_seconds").tag("job", COMPLETING_JOB).timer();
        assertThat(timer).as("completing run recorded into the timer").isNotNull();
        assertThat(timer.count()).isPositive();
    }

    @Test
    void throwing_job_persists_failed_run_and_does_not_propagate() {
        assertThatCode(throwingJob::runOnce)
                .as("aspect swallows the throw so the scheduler tick survives")
                .doesNotThrowAnyException();

        JobRun latest = jobRuns.findLatestByJobName(THROWING_JOB).orElseThrow();
        assertThat(latest.getStatus()).isEqualTo(JobRun.Status.FAILED);
        assertThat(latest.getFinishedAt()).isNotNull();
        assertThat(latest.getError()).contains("boom");

        Timer timer = meters.find("fls_job_duration_seconds").tag("job", THROWING_JOB).timer();
        assertThat(timer).as("failed run recorded into the timer").isNotNull();
        assertThat(timer.count()).isPositive();
    }

    @TestConfiguration
    static class TestJobConfig {
        @Bean
        CompletingJob completingJob() {
            return new CompletingJob();
        }

        @Bean
        ThrowingJob throwingJob() {
            return new ThrowingJob();
        }
    }

    @MeasuredJob(name = COMPLETING_JOB, cron = "0 0 0 1 1 ?", description = "IT completing job")
    static class CompletingJob {
        public String runOnce() {
            return "processed=3";
        }
    }

    @MeasuredJob(name = THROWING_JOB, cron = "0 0 0 1 1 ?", description = "IT throwing job")
    static class ThrowingJob {
        public String runOnce() {
            throw new IllegalStateException("boom");
        }
    }
}

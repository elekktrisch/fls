package ch.alpenflight.platform.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Aspect
@Component
public class MeasuredJobAspect {

    private static final Logger LOG = LoggerFactory.getLogger(MeasuredJobAspect.class);
    private static final String TIMER_NAME = "fls_job_duration_seconds";

    private final JobRunRepository jobRuns;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TransactionTemplate ownTxSoRunRecordSurvivesRollback;

    public MeasuredJobAspect(JobRunRepository jobRuns,
                             MeterRegistry meters,
                             Clock clock,
                             PlatformTransactionManager txManager) {
        this.jobRuns = jobRuns;
        this.meters = meters;
        this.clock = clock;
        this.ownTxSoRunRecordSurvivesRollback = new TransactionTemplate(txManager);
        this.ownTxSoRunRecordSurvivesRollback.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Around("@within(measured) && execution(* runOnce(..))")
    public @Nullable Object measure(ProceedingJoinPoint pjp, MeasuredJob measured) {
        String jobName = measured.name();
        UUID runId = openRun(jobName);
        Timer.Sample sample = Timer.start(meters);
        try {
            Object result = pjp.proceed();
            sample.stop(timer(jobName, "completed"));
            recordCompleted(runId, summaryOf(result));
            return result;
        } catch (Throwable failure) {
            sample.stop(timer(jobName, "failed"));
            recordFailed(runId, jobName, failure);
            return null;
        }
    }

    private UUID openRun(String jobName) {
        JobRun run = ownTxSoRunRecordSurvivesRollback.execute(status -> jobRuns.save(JobRun.start(jobName, clock)));
        UUID id = run == null ? null : run.getId();
        if (id == null) {
            throw new IllegalStateException("JobRun id null after save for job " + jobName);
        }
        return id;
    }

    private void recordCompleted(UUID runId, @Nullable String summary) {
        transition(runId, run -> run.complete(summary, clock));
    }

    private void recordFailed(UUID runId, String jobName, Throwable failure) {
        LOG.warn("MeasuredJob '{}' failed — recording FAILED run", jobName, failure);
        String message = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        transition(runId, run -> run.fail(message, clock));
    }

    private void transition(UUID runId, Consumer<JobRun> mutation) {
        ownTxSoRunRecordSurvivesRollback.executeWithoutResult(status ->
                jobRuns.findById(runId).ifPresent(run -> {
                    mutation.accept(run);
                    jobRuns.save(run);
                }));
    }

    private Timer timer(String jobName, String outcome) {
        return Timer.builder(TIMER_NAME)
                .description("Business-job run duration")
                .tag("job", jobName)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meters);
    }

    private static @Nullable String summaryOf(@Nullable Object result) {
        return result == null ? null : String.valueOf(result);
    }
}

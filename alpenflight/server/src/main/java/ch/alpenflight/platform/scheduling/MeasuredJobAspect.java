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

/**
 * Wraps the {@code runOnce} method of every {@link MeasuredJob}-annotated bean.
 * On each run it opens a {@link JobRun} record ({@code RUNNING}), proceeds, then
 * stamps {@code COMPLETED} (with the body's return value as summary) or
 * {@code FAILED} (with the throwable message). It also times the run into the
 * {@code fls_job_duration_seconds{job=…}} Micrometer histogram.
 *
 * <p>A job whose body throws is recorded {@code FAILED} and the throwable is
 * <strong>swallowed</strong> — the scheduler tick (or the "Run now" endpoint)
 * survives one bad job (J-15 AC-edge #8). The {@link JobRun} writes run in their
 * own {@code REQUIRES_NEW} transaction so the FAILED record commits even though
 * the body's transaction rolls back; programmatic {@link TransactionTemplate}
 * (not {@code @Transactional}) sidesteps the AOP self-invocation trap that would
 * otherwise ride the writes on the doomed transaction.
 */
@Aspect
@Component
public class MeasuredJobAspect {

    private static final Logger LOG = LoggerFactory.getLogger(MeasuredJobAspect.class);
    private static final String TIMER_NAME = "fls_job_duration_seconds";

    private final JobRunRepository jobRuns;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public MeasuredJobAspect(JobRunRepository jobRuns,
                             MeterRegistry meters,
                             Clock clock,
                             PlatformTransactionManager txManager) {
        this.jobRuns = jobRuns;
        this.meters = meters;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            // Swallow: one failing job must not crash the scheduler tick or the
            // "Run now" caller. The FAILED JobRun is the durable signal.
            return null;
        }
    }

    private UUID openRun(String jobName) {
        JobRun run = requiresNew.execute(status -> jobRuns.save(JobRun.start(jobName, clock)));
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
        requiresNew.executeWithoutResult(status ->
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

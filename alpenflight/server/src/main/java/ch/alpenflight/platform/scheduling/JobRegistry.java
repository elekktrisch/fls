package ch.alpenflight.platform.scheduling;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link BusinessJob} bean (each annotated {@link MeasuredJob})
 * and backs the {@code /system/jobs} admin console: {@link #list()} projects
 * each job's stable name + cron + latest {@link JobRun}, and {@link #runOnce}
 * invokes one by name.
 *
 * <p>Invocation goes through the bean proxy so {@link MeasuredJobAspect} wraps
 * the run (opens the {@link JobRun}, records completed/failed, times it). The
 * aspect swallows a job-body throw and writes the {@code FAILED} record in its
 * own transaction, so this method re-reads the latest run afterwards rather
 * than trusting the (possibly {@code null}) return value.
 */
@Component
public class JobRegistry {

    private final JobRunRepository jobRuns;
    private final Map<String, BusinessJob> byName;

    public JobRegistry(List<BusinessJob> jobs, JobRunRepository jobRuns) {
        this.jobRuns = jobRuns;
        Map<String, BusinessJob> map = new LinkedHashMap<>();
        for (BusinessJob job : jobs) {
            MeasuredJob measured = measuredOf(job);
            map.put(measured.name(), job);
        }
        this.byName = Map.copyOf(map);
    }

    /** Every registered job with its declared cron + latest run, name-ordered. */
    public List<JobDescriptor> list() {
        return byName.entrySet().stream()
                .map(e -> descriptor(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(JobDescriptor::name))
                .toList();
    }

    /**
     * Runs the named job once (aspect-instrumented) and returns the resulting
     * {@link JobRun}. An unknown name is a {@link JobNotFoundException} the web
     * layer maps to 404 — the console never offers a name that isn't listed, so
     * this only fires on a stale client or a probe.
     */
    public JobRun runOnce(String name) {
        BusinessJob job = byName.get(name);
        if (job == null) {
            throw new JobNotFoundException(name);
        }
        job.runOnce();
        return jobRuns.findLatestByJobName(name)
                .orElseThrow(() -> new IllegalStateException(
                        "No JobRun recorded for '" + name + "' after runOnce — is @MeasuredJob missing?"));
    }

    private JobDescriptor descriptor(String name, BusinessJob job) {
        MeasuredJob measured = measuredOf(job);
        return new JobDescriptor(name, measured.cron(), measured.description(),
                jobRuns.findLatestByJobName(name).orElse(null));
    }

    private static MeasuredJob measuredOf(BusinessJob job) {
        MeasuredJob measured = AnnotationUtils.findAnnotation(
                AopUtils.getTargetClass(job), MeasuredJob.class);
        if (measured == null) {
            throw new IllegalStateException(
                    "BusinessJob " + job.getClass().getName() + " is missing @MeasuredJob");
        }
        return measured;
    }

    /** One registered job's console projection: identity + last-run snapshot. */
    public record JobDescriptor(String name, String cron, String description,
                                @Nullable JobRun lastRun) {
    }

    /** Thrown when {@link #runOnce} is asked for a name no bean registers. */
    public static final class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String name) {
            super("No registered job named '" + name + "'");
        }
    }
}

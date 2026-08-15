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

    public List<JobDescriptor> list() {
        return byName.entrySet().stream()
                .map(e -> descriptor(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(JobDescriptor::name))
                .toList();
    }

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

    public record JobDescriptor(String name, String cron, String description,
                                @Nullable JobRun lastRun) {
    }

    public static final class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String name) {
            super("No registered job named '" + name + "'");
        }
    }
}

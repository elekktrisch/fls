package ch.alpenflight.platform.scheduling;

import ch.alpenflight.platform.scheduling.JobRegistry.JobDescriptor;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public final class JobDtos {

    private JobDtos() {}

    public record JobResponse(String name, String cron, String description,
                              @Nullable JobRunResponse lastRun) {
        public static JobResponse from(JobDescriptor descriptor) {
            return new JobResponse(descriptor.name(), descriptor.cron(), descriptor.description(),
                    JobRunResponse.from(descriptor.lastRun()));
        }
    }

    public record JobRunResponse(JobRun.Status status,
                                 @Nullable Instant startedAt,
                                 @Nullable Instant finishedAt,
                                 @Nullable String summary) {
        public static @Nullable JobRunResponse from(@Nullable JobRun run) {
            return run == null ? null : of(run);
        }

        public static JobRunResponse of(JobRun run) {
            String summary = run.getStatus() == JobRun.Status.FAILED ? run.getError() : run.getSummary();
            return new JobRunResponse(run.getStatus(), run.getStartedAt(), run.getFinishedAt(), summary);
        }
    }
}

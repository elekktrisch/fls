package ch.alpenflight.platform.scheduling;

import ch.alpenflight.platform.scheduling.JobRegistry.JobDescriptor;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTOs for the {@code /api/v1/admin/jobs} console surface. Projects the
 * {@link JobRun} entity to the exact fields the console renders — never the row
 * id or the raw error stack — so entities never leak past the controller.
 */
public final class JobDtos {

    private JobDtos() {}

    /** One job in the console list: identity + cron + its last run (or none). */
    public record JobResponse(String name, String cron, String description,
                              @Nullable JobRunResponse lastRun) {
        public static JobResponse from(JobDescriptor descriptor) {
            return new JobResponse(descriptor.name(), descriptor.cron(), descriptor.description(),
                    JobRunResponse.from(descriptor.lastRun()));
        }
    }

    /**
     * A single run's console projection. {@code status} drives the badge
     * (RUNNING / COMPLETED / FAILED); {@code summary} carries the completed
     * run's non-PII outcome or the failure message. Absent when the job never
     * ran (the list's {@code lastRun} is then {@code null} ↔ NEVER_RUN).
     */
    public record JobRunResponse(JobRun.Status status,
                                 @Nullable Instant startedAt,
                                 @Nullable Instant finishedAt,
                                 @Nullable String summary) {
        public static @Nullable JobRunResponse from(@Nullable JobRun run) {
            return run == null ? null : of(run);
        }

        /** Projection of a known-present run (the "Run now" response). */
        public static JobRunResponse of(JobRun run) {
            String summary = run.getStatus() == JobRun.Status.FAILED ? run.getError() : run.getSummary();
            return new JobRunResponse(run.getStatus(), run.getStartedAt(), run.getFinishedAt(), summary);
        }
    }
}

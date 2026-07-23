package ch.alpenflight.platform.scheduling;

import org.jspecify.annotations.Nullable;

/**
 * Contract for a business job the {@code /system/jobs} console lists and runs
 * on demand. Every implementation also carries {@link MeasuredJob} (the stable
 * registry key + cron + description) so the {@link JobRegistry} can collect it
 * and the {@link MeasuredJobAspect} can instrument its {@link #runOnce()} call.
 *
 * <p>{@link #runOnce()} is the single cross-tenant entry point the registry
 * invokes for a "Run now"; jobs whose scheduled tick iterates clubs reproduce
 * that iteration here so a manual run and the cron path do the same work. The
 * aspect wraps this call: it opens a {@link JobRun}, records
 * {@code COMPLETED}/{@code FAILED}, and captures the returned value as the run
 * summary ({@code String.valueOf(result)}) — so a job returns a summary object
 * whose {@code toString()} is a non-PII one-line outcome (counts, ids), or
 * {@code null} when there is nothing to summarise.
 */
public interface BusinessJob {

    /**
     * Runs the job once, cross-tenant. Instrumented by {@link MeasuredJobAspect}
     * (started → completed/failed + the {@code fls_job_duration_seconds} timer).
     *
     * @return a non-PII run summary the console surfaces, or {@code null}.
     */
    @Nullable Object runOnce();
}

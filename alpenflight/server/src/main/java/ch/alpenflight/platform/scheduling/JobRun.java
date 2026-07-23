package ch.alpenflight.platform.scheduling;

import ch.alpenflight.platform.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One execution of a {@link MeasuredJob}. Append-only: {@link MeasuredJobAspect}
 * inserts a row at {@code start}, then the same row is transitioned to
 * {@code completed} / {@code failed} in place. The console reads the most
 * recent row per {@code jobName} — {@code NEVER_RUN} is the absence of any row.
 *
 * <p>Platform / cross-tenant table (jobs run unscoped across all clubs; the
 * console is {@code SYSTEM_ADMINISTRATOR}-gated, no tenant): no {@code @TenantId}.
 * Mirrors {@code t_deployment} / {@code t_migration_run} — greenfield platform
 * tables that carry no legacy origin and no tenant discriminator.
 *
 * <p>Per ADR 0022 directive 2 the lifecycle lives here as Java mutators
 * ({@link #complete}, {@link #fail}), not as a schema CHECK.
 */
@Entity
@Table(name = "t_job_run")
public class JobRun {

    /** Terminal-or-in-flight state of a single run. Absence ↔ {@code NEVER_RUN}. */
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED
    }

    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int MAX_ERROR_LENGTH = 4000;

    @Id
    @UuidV7
    private @Nullable UUID id;

    @Column(name = "job_name", nullable = false, length = 128, updatable = false)
    private String jobName = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.RUNNING;

    @Column(name = "started_at", nullable = false, updatable = false)
    private @Nullable Instant startedAt;

    @Column(name = "finished_at")
    private @Nullable Instant finishedAt;

    @Column(name = "summary", columnDefinition = "text")
    private @Nullable String summary;

    @Column(name = "error", columnDefinition = "text")
    private @Nullable String error;

    protected JobRun() {
        // JPA.
    }

    /** Opens a run in {@link Status#RUNNING} at {@code clock}'s instant. */
    public static JobRun start(String jobName, Clock clock) {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("jobName must not be blank");
        }
        JobRun run = new JobRun();
        run.jobName = jobName;
        run.status = Status.RUNNING;
        run.startedAt = Instant.now(clock);
        return run;
    }

    /** RUNNING → COMPLETED, stamping {@code finishedAt} + the run summary. */
    public void complete(@Nullable String summary, Clock clock) {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("complete() only valid from RUNNING, was " + status);
        }
        this.status = Status.COMPLETED;
        this.summary = truncate(summary, MAX_SUMMARY_LENGTH);
        this.finishedAt = Instant.now(clock);
    }

    /** RUNNING → FAILED, stamping {@code finishedAt} + the failure message. */
    public void fail(@Nullable String error, Clock clock) {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("fail() only valid from RUNNING, was " + status);
        }
        this.status = Status.FAILED;
        this.error = truncate(error, MAX_ERROR_LENGTH);
        this.finishedAt = Instant.now(clock);
    }

    private static @Nullable String truncate(@Nullable String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public @Nullable UUID getId() {
        return id;
    }

    public String getJobName() {
        return jobName;
    }

    public Status getStatus() {
        return status;
    }

    public @Nullable Instant getStartedAt() {
        return startedAt;
    }

    public @Nullable Instant getFinishedAt() {
        return finishedAt;
    }

    public @Nullable String getSummary() {
        return summary;
    }

    public @Nullable String getError() {
        return error;
    }
}

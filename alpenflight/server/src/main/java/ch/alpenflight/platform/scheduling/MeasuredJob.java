package ch.alpenflight.platform.scheduling;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a business job the {@code /system/jobs} console lists and
 * runs. The {@link #name()} is the stable registry key (kebab-case, e.g.
 * {@code daily-flight-validation}) — it keys the persisted {@link JobRun}
 * last-run record and appears in the {@code fls_job_duration_seconds{job=…}}
 * timer tag, so it must stay stable across renames of the Java class.
 *
 * <p>{@link MeasuredJobAspect} wraps the annotated class's {@code runOnce}
 * method (the {@code @Around} pointcut), recording started → completed/failed
 * on every invocation. A job whose body throws is recorded {@code failed}
 * and the throw is swallowed at the advice — the scheduler tick survives
 * (J-15 AC-edge #8).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MeasuredJob {

    /** Stable registry key + timer tag + {@link JobRun} join key. */
    String name();

    /**
     * Cron expression surfaced in the console for operator context. Purely
     * descriptive here — the actual {@code @Scheduled} trigger lives on the
     * job's method; this mirrors it for the registry view.
     */
    String cron() default "";

    /** Human-readable label for the console's job list. */
    String description() default "";
}

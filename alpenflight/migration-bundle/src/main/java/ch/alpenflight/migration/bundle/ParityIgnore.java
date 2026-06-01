package ch.alpenflight.migration.bundle;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Column-level opt-out from the parity oracle's sampled-value diff (S-187).
 * Free-text fields, denormalized caches, and recomputed derived values are
 * legitimate divergence — the oracle records the column but does not assert
 * equality. The mapper still round-trips the column.
 *
 * <p>Applied to a {@code static final String} field on the mapper class
 * whose value matches the column name in {@link Mapper#columns()}.
 * {@link ParityMarkers} reflects over the mapper class at harness-init
 * time to build the per-mapper ignore set.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ParityIgnore {
}

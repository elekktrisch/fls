package ch.alpenflight.migration.bundle;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Column-level opt-in marker for the parity oracle's strict-equality diff
 * (S-187). Sentinel columns are checked with zero tolerance — every legacy
 * value MUST match the new value after round-trip. Used for foreign keys,
 * status enums, monetary amounts, timestamps, and generated columns.
 *
 * <p>Applied to a {@code static final String} field on the mapper class
 * whose value matches the column name in {@link Mapper#columns()}.
 * {@link ParityMarkers} reflects over the mapper class at harness-init
 * time to build the per-mapper sentinel set.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ParitySentinel {
}

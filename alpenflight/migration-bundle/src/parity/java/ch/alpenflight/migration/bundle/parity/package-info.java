/**
 * Parity oracle harness for the migration bundle (S-187).
 *
 * <p>Round-trips a synthetic legacy fixture through every {@code Mapper}'s
 * producer side (legacy MSSQL → in-memory tar.gz) and consumer side (tar.gz →
 * Postgres) and asserts {@code summary.json.passed && totalDeltas==0 &&
 * fkOrphans==0}. Reports under {@code build/reports/parity/<run-id>/}.
 *
 * <p>This package lives in the {@code src/parity/java/} source set rather
 * than the main test source set: Testcontainers MSSQL + Postgres + JDBC
 * drivers are too heavy for the {@code ./gradlew test} budget. The
 * {@code parityTest} Gradle task is the entry point.
 *
 * <p><strong>Vertical-slice scope (this story).</strong> The harness wires
 * the round-trip plumbing end-to-end for three identity-group mappers
 * (Country + Club + User) — enough to prove the producer / envelope /
 * consumer / diff axis. The remaining 25 mappers and the coverage gates
 * (S-187 design notes (a)–(d)), producer-drop reconciliation, two-pass
 * UPDATE simulation, composite {@code legacy_id_map_location} resolution,
 * negative-path bundle-reject cases, and the mutation-smoke self-test are
 * deferred to {@code S-187a}. The deferred coverage is what changes the
 * harness from "plumbing exists" to "all 28 mappers exercised".
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.migration.bundle.parity;

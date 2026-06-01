/**
 * Parity oracle harness for the migration bundle.
 *
 * <p>Round-trips a synthetic legacy fixture through every {@code Mapper}'s
 * producer side (legacy MSSQL → in-memory tar.gz) and consumer side
 * (tar.gz → Postgres) and asserts byte-identical row counts per Club.
 * Reports under {@code build/reports/parity/<run-id>/}.
 *
 * <p>This package lives in a dedicated {@code src/parity/java/} source
 * set rather than the main test source set: the MSSQL + Postgres
 * containers + JDBC drivers + Flyway are too heavy for the
 * {@code ./gradlew test} budget. The {@code parityTest} Gradle task is
 * the entry point.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.migration.bundle.parity;

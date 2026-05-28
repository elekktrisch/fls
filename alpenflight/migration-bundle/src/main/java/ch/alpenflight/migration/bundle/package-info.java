/**
 * Shared legacy-FLS → AlpenFlight migration mappers + parity oracle harness.
 *
 * <p>The library is consumed by two sides of the migration flow:
 * <ul>
 *   <li><strong>S-139 JAR exporter</strong> — reads legacy SQL Server rows and writes
 *       NDJSON entries into the encrypted bundle.</li>
 *   <li><strong>S-141 server ingest pipeline</strong> — reads NDJSON entries from the
 *       decrypted bundle stream and binds them to {@code StatelessSession}
 *       prepared-statement parameters.</li>
 * </ul>
 *
 * <p>The library carries no business logic; schema rules live in the new-stack
 * Flyway baseline and Hibernate entities. This module owns only the legacy↔new
 * column mappings, the per-bundle {@code legacy_id_map_<entity>} temp-table API,
 * the topological insert order, and the parity-oracle harness.
 *
 * <p>See {@code docs/modernization/stories/implemented/S-016-data-migration-script.md}
 * for the load-bearing design decisions.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.migration.bundle;

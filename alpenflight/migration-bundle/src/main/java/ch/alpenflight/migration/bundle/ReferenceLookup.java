package ch.alpenflight.migration.bundle;

/**
 * Declares that a mapper's {@code column} carries a Flyway-seed reference row
 * encoded as the synthetic UUID {@code new UUID(0, legacyIntId)} (see
 * {@link Coercions#legacyIntIdToUuidString}), and that the real seed PK lives
 * in {@code seedTable} keyed by its {@code legacy_int_id} column.
 *
 * <p>Unlike {@link Mapper#foreignKeys()} (whose targets are {@link EntityType}
 * bundle entities resolved via the per-bundle {@code legacy_id_map_<entity>}
 * temp tables), reference-lookup targets are FLIGHT-group lookup tables that
 * ship in the V2/V3 Flyway seed — they are never part of a bundle, so the
 * ingest pipeline resolves them structurally by joining
 * {@code <seedTable>.id WHERE legacy_int_id = <legacyIntId>}. The enabling
 * {@code ux_<seedTable>_legacy_int_id} unique index is what makes the join
 * a single-row point lookup.
 *
 * <p>Both identifiers are interpolated into SQL by the ingest pipeline, so the
 * resolver re-validates them against the {@code [A-Za-z0-9_]+} allow-list the
 * INSERT path already enforces — no caller-supplied identifier reaches the DB
 * unchecked.
 */
public record ReferenceLookup(String column, String seedTable) {

    public ReferenceLookup {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("ReferenceLookup column must be non-blank");
        }
        if (seedTable == null || seedTable.isBlank()) {
            throw new IllegalArgumentException("ReferenceLookup seedTable must be non-blank");
        }
    }
}

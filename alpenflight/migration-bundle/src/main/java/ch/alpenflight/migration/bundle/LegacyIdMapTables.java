package ch.alpenflight.migration.bundle;

/**
 * Naming utility for the per-bundle {@code legacy_id_map_<entity>} temporary
 * tables. S-141 owns the table lifecycle ({@code ON COMMIT DROP}); S-016 owns
 * the byte format and the names so the two stories don't drift.
 */
public final class LegacyIdMapTables {

    private static final String PREFIX = "legacy_id_map_";

    private LegacyIdMapTables() { }

    public static String temporaryTableName(EntityType entity) {
        return PREFIX + entity.temporaryTableSuffix();
    }

    /**
     * Returns both {@code legacy_guid} and {@code new_uuid} so the caller can
     * map a single batched query back to its 500-row input array — Postgres
     * does not guarantee result-row order for {@code = ANY(?)} predicates, so
     * a single-column result would force callers to re-issue or re-order.
     */
    public static String resolveForeignKeyArrayQuery(EntityType target) {
        return "SELECT legacy_guid, new_uuid FROM "
                + temporaryTableName(target)
                + " WHERE legacy_guid = ANY(?::uuid[])";
    }
}

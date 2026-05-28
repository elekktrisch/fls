package ch.alpenflight.migration.bundle;

/**
 * Naming utility for the per-bundle {@code legacy_id_map_<entity>} temp tables.
 *
 * <p>S-141 owns the temp-table lifecycle ({@code ON COMMIT DROP}) and S-016 owns
 * the byte format + naming so the two stories don't drift. Every mapper that
 * resolves a cross-entity FK calls {@link #tempTableName(EntityType)} when
 * generating its {@code WHERE legacy_guid = ANY(?::uuid[])} batch prefetch.
 */
public final class LegacyIdMapTables {

    private static final String PREFIX = "legacy_id_map_";

    private LegacyIdMapTables() { }

    /** {@code legacy_id_map_<entity-suffix>} — the per-entity temp-table name. */
    public static String tempTableName(EntityType entity) {
        return PREFIX + entity.tempTableSuffix();
    }

    /**
     * Parameterised SQL that resolves a batch of legacy GUIDs to the new UUIDs via
     * the per-entity temp table. The single bind parameter is a {@code UUID[]}.
     */
    public static String resolveFkArrayQuery(EntityType target) {
        return "SELECT legacy_guid, new_uuid FROM "
                + tempTableName(target)
                + " WHERE legacy_guid = ANY(?::uuid[])";
    }
}

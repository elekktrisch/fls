package ch.alpenflight.migration.bundle;

public final class LegacyIdMapTables {

    private static final String PREFIX = "legacy_id_map_";

    private LegacyIdMapTables() { }

    public static String temporaryTableName(EntityType entity) {
        return PREFIX + entity.temporaryTableSuffix();
    }

    public static String resolveForeignKeyArrayQuery(EntityType target) {
        return "SELECT legacy_guid, new_uuid FROM "
                + temporaryTableName(target)
                + " WHERE legacy_guid = ANY(?::uuid[])";
    }
}

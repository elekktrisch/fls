package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;

/**
 * SYSTEM_GLOBAL reference — V2 seeds the canonical UUID v7 rows; ingest
 * resolves through the {@code t_country.legacy_int_id} column added by S-012,
 * not through the per-bundle {@code legacy_id_map_*} temp tables.
 */
public final class CountryMapper implements Mapper {

    private static final String[] COLUMNS = {
            "id",
            "country_code",
            "country_name",
            "legacy_int_id"
    };

    @Override
    public EntityType entityType() {
        return EntityType.COUNTRY;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }
}

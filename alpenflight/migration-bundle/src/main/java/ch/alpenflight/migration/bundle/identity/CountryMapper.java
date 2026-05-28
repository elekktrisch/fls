package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;

/**
 * Mapper for the legacy {@code Country} reference table.
 *
 * <p>Country is a SYSTEM_GLOBAL reference: the new-stack V2 seed installs a
 * canonical set of rows with fixed UUID v7 literals. Resolution at ingest time
 * goes through the {@code t_country.legacy_int_id} column that S-012 added
 * specifically as S-016's cutover hook — NOT through the per-bundle
 * {@code legacy_id_map_*} temp tables.
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

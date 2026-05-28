package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;

/**
 * Walking-skeleton mapper for the legacy {@code Country} reference table.
 *
 * <p>Country is a SYSTEM_GLOBAL reference: the new-stack V2 seed installs a
 * canonical set of rows with fixed UUID v7 literals. Resolution at ingest time
 * goes through the {@code t_country.legacy_int_id} column that S-012 added
 * specifically as this story's cutover hook — NOT through the per-bundle
 * {@code legacy_id_map_*} temp tables.
 *
 * <p>This concrete mapper exists to nail down the contract; the
 * {@code writeNdjson} + {@code readEntity} methods (with their Jackson + JDBC
 * signatures) land with the follow-up story.
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

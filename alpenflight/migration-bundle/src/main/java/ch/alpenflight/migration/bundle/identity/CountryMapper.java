package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParitySentinel;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * SYSTEM_GLOBAL reference. V2 seeds the canonical {@code t_country} rows by
 * ISO 3166 code; the bundle carries (legacy_guid, iso2_code) pairs that
 * S-141 resolves through a {@code legacy_id_map_country} populate query.
 *
 * <p>Walking-skeleton sample for the {@link Mapper} contract — demonstrates
 * the column-list shape, the parity-sentinel marker, and the bidirectional
 * round-trip discipline. The full per-package mappers land at S-184.
 */
public final class CountryMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";

    @ParitySentinel
    static final String ISO2_CODE = "iso2_code";

    private static final String[] COLUMNS = { LEGACY_GUID, ISO2_CODE };

    @Override
    public EntityType entityType() {
        return EntityType.COUNTRY;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of();
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("CountryId"));
        target.writeStringField(ISO2_CODE, source.getString("CountryCodeIso2"));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        target.setObject(1, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(2, source.get(ISO2_CODE).asText());
    }
}

package ch.alpenflight.migration.bundle.identity;

import ch.alpenflight.migration.bundle.Coercions;
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
import java.util.Locale;
import java.util.UUID;

/**
 * SYSTEM_GLOBAL reference. V2 seeds {@code t_language} (8 canonical codes);
 * legacy {@code Languages.LanguageKey} resolves via case-folded lookup
 * against {@code t_language.code}. V2 does not carry {@code legacy_int_id}
 * on the language row — the join is by code, not by id.
 *
 * <p>The legacy primary key is {@code int}; this mapper widens it through
 * {@link Coercions#legacyIntIdToUuidString} into the new-stack's fixed
 * {@code legacy_id_map_*} byte format. Every mapper that emits an FK to a
 * language row uses the same encoding (see {@code UserMapper.language_id}).
 *
 * <p>S-141 ingest populates {@code legacy_id_map_language} by joining
 * {@code (legacy_guid, code)} bundle pairs against the V2 seed; drift
 * (a legacy LanguageKey not in the V2 seed set) is surfaced fail-closed
 * per ADR 0022 directive 1 with the explicit
 * {@code BUNDLE_LANGUAGE_NOT_SEEDED} error code, not silently defaulted.
 */
public final class LanguageMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";

    @ParitySentinel
    static final String CODE = "code";

    private static final String[] COLUMNS = { LEGACY_GUID, CODE };

    @Override
    public EntityType entityType() {
        return EntityType.LANGUAGE;
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
        target.writeStringField(LEGACY_GUID,
                Coercions.legacyIntIdToUuidString(source.getInt("LanguageId")));
        target.writeStringField(CODE,
                source.getString("LanguageKey").toLowerCase(Locale.ROOT));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        target.setObject(1, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setString(2, source.get(CODE).asText());
    }
}

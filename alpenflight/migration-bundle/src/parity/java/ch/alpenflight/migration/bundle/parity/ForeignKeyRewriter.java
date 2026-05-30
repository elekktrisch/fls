package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pre-bind rewrite of FK columns on a FULL_PORT mapper row — the in-process
 * stand-in for S-141's FK resolution stage. For each {@code mapper.foreignKeys()}
 * target whose bundle column carries a legacy GUID, look up the new-stack
 * UUID via {@link LegacyIdMapPopulator.Maps} and replace the field in the
 * {@link ObjectNode}. The mapper's {@code readEntity} then binds the
 * already-resolved value.
 *
 * <p>Column-name convention used by the vertical-slice mappers (Club /
 * User): {@code <entityType.name().toLowerCase()>_id}. ClubMapper's
 * {@code country_id} / {@code club_state_id}; UserMapper's {@code club_id} /
 * {@code person_id} / {@code language_id}. S-187a generalises to
 * non-canonical names (e.g. {@code Aircraft.homebase_id} targets LOCATION).
 *
 * <p>FULL_PORT-to-FULL_PORT FKs (e.g. {@code User.club_id} → CLUB) skip the
 * rewrite — legacy GUID = new UUID per ADR 0019 legacy-GUID preservation,
 * and no {@code legacy_id_map_club} is populated in the vertical slice.
 * Drift-guard via {@link #isSystemGlobalTarget}.
 */
public final class ForeignKeyRewriter {

    private ForeignKeyRewriter() { }

    public static void rewrite(
            Mapper mapper, ObjectNode row, LegacyIdMapPopulator.Maps maps) {
        for (EntityType target : mapper.foreignKeys()) {
            if (!isSystemGlobalTarget(target)) {
                continue;
            }
            String fieldName = conventionalForeignKeyFieldName(target);
            JsonNode currentValue = row.get(fieldName);
            if (currentValue == null || currentValue.isNull()) {
                continue;
            }
            UUID legacyGuid = UUID.fromString(currentValue.asText());
            UUID resolved = maps.requireFor(target).get(legacyGuid);
            if (resolved == null) {
                throw new IllegalStateException(
                        "FK " + fieldName + " value " + legacyGuid
                                + " has no resolution in legacy_id_map_" + target
                                + " — the SYSTEM_GLOBAL bundle entry must enumerate "
                                + "every value the producer emitted, otherwise the "
                                + "consumer cannot translate FKs to new-stack UUIDs.");
            }
            row.put(fieldName, resolved.toString());
        }
    }

    private static boolean isSystemGlobalTarget(EntityType target) {
        return switch (target) {
            case COUNTRY, LANGUAGE, CLUB_STATE -> true;
            // S-187a widens to the FLIGHT-group + accounting reference tables.
            default -> false;
        };
    }

    private static String conventionalForeignKeyFieldName(EntityType target) {
        return target.name().toLowerCase(Locale.ROOT) + "_id";
    }
}

package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
            default -> false;
        };
    }

    private static String conventionalForeignKeyFieldName(EntityType target) {
        return target.name().toLowerCase(Locale.ROOT) + "_id";
    }
}

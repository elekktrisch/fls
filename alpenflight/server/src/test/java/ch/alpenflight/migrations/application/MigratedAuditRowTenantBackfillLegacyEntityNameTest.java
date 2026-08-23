package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MigratedAuditRowTenantBackfillLegacyEntityNameTest {

    private static final String LEGACY_DB_ENTITIES_DIRECTORY =
            "flsserver/src/FLS.Server.Data/DbEntities";

    @Test
    void everyEntityTheBackfillResolvesCarriesTheNameLegacyWroteIntoTheAuditRow()
            throws IOException {
        Set<String> legacyEntityClassNames = legacyEntityClassNames();
        List<String> namesNoLegacyClassCarries = new ArrayList<>();
        for (EntityType entity : EntityType.values()) {
            if (!MigratedAuditRowTenantBackfill.aRowOfThisEntityCanNameItsOwnClub(entity)) {
                continue;
            }
            String derived = MigratedAuditRowTenantBackfill.legacyEntityNameOf(entity);
            if (!legacyEntityClassNames.contains(derived)) {
                namesNoLegacyClassCarries.add(entity + " → " + derived);
            }
        }
        assertThat(namesNoLegacyClassCarries)
                .as("the backfill matches audit rows on target_entity_type, which legacy writes "
                        + "as the DbEntity class name; a derived name no legacy class carries "
                        + "matches nothing and back-fills no row at all")
                .isEmpty();
    }

    @Test
    void theResolvedSetCoversTheEntitiesTheScreenNeedsSoTheGuardIsNotVacuous() {
        List<EntityType> resolved = Stream.of(EntityType.values())
                .filter(MigratedAuditRowTenantBackfill::aRowOfThisEntityCanNameItsOwnClub)
                .toList();

        assertThat(resolved)
                .contains(EntityType.USER, EntityType.FLIGHT, EntityType.CLUB,
                        EntityType.AIRCRAFT_RESERVATION, EntityType.DELIVERY);
        assertThat(resolved)
                .as("AUDIT_LOG describes no owning entity, and a fanned-out entity has one "
                        + "replica per club, so neither names a single club")
                .doesNotContain(EntityType.AUDIT_LOG, EntityType.LOCATION,
                        EntityType.INOUTBOUND_POINT);
    }

    private static Set<String> legacyEntityClassNames() throws IOException {
        Path directory = locateLegacyDbEntitiesDirectory();
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".cs"))
                    .map(name -> name.substring(0, name.length() - ".cs".length()))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static Path locateLegacyDbEntitiesDirectory() {
        Path probe = Path.of("").toAbsolutePath();
        while (probe != null) {
            Path candidate = probe.resolve(LEGACY_DB_ENTITIES_DIRECTORY);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            probe = probe.getParent();
        }
        throw new IllegalStateException(
                "No " + LEGACY_DB_ENTITIES_DIRECTORY + " above " + Path.of("").toAbsolutePath());
    }
}

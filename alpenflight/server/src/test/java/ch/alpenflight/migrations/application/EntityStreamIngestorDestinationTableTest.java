package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityStreamIngestorDestinationTableTest {

    @Test
    void everyBoundMapperIngestsIntoTheTableItsBindingDeclares() {
        List<String> divergences = new ArrayList<>();
        for (Mapper mapper : KnownMappers.all()) {
            EntityType entity = mapper.entityType();
            if (!MapperLegacyBindings.isRegistered(entity)) {
                continue;
            }
            String declared = MapperLegacyBindings.newSchemaTable(entity);
            String ingested = EntityStreamIngestor.destinationTableFor(entity);
            if (!declared.equals(ingested)) {
                divergences.add(entity + ": the binding declares " + declared
                        + " but the ingest writes into " + ingested);
            }
        }
        assertThat(divergences)
                .as("EntityStreamIngestor must write each entity into the table its "
                        + "MapperLegacyBindings entry declares. A name derived from the enum "
                        + "instead diverges silently whenever the destination table carries a "
                        + "different name (AUDIT_LOG -> t_mutation_audit_event), and the ingest "
                        + "then fails on a missing relation at the ~20-min fan-out rather than "
                        + "at build time: %s", divergences)
                .isEmpty();
    }

    @Test
    void theIngestInsertStatementNamesTheDeclaredDestinationTable() {
        EntityStreamIngestor ingestor = new EntityStreamIngestor(KnownMappers.all());
        List<String> divergences = new ArrayList<>();
        for (Mapper mapper : KnownMappers.all()) {
            EntityType entity = mapper.entityType();
            if (!MapperLegacyBindings.isRegistered(entity)) {
                continue;
            }
            String expectedPrefix =
                    "INSERT INTO " + MapperLegacyBindings.newSchemaTable(entity) + " (";
            String insert = ingestor.insertStatementFor(entity);
            if (!insert.startsWith(expectedPrefix)) {
                divergences.add(entity + ": " + insert.substring(
                        0, Math.min(insert.length(), expectedPrefix.length() + 20)));
            }
        }
        assertThat(divergences)
                .as("The INSERT the ingest prepares must target the declared destination "
                        + "table, not a name derived from the enum: %s", divergences)
                .isEmpty();
    }
}

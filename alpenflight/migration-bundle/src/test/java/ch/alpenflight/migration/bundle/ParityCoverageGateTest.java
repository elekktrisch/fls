package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.ParityCoverageGate.Inputs;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParityCoverageGateTest {

    private static final Set<String> TWO_CLUBS = Set.of("club-1", "club-2");

    private static Inputs satisfied() {
        return new Inputs(
                Set.of(EntityType.COUNTRY, EntityType.USER),
                Set.of(EntityType.COUNTRY),
                TWO_CLUBS,
                Map.of(EntityType.USER, Set.of("club-1", "club-2")),
                Map.of(EntityType.COUNTRY, 5L),
                Set.of(EntityType.COUNTRY, EntityType.USER),
                Set.of("Settings"),
                Map.of("Settings", 0L));
    }

    @Test
    void fullySeededFixtureReportsNoGaps() {
        assertThat(ParityCoverageGate.diagnose(satisfied())).isEmpty();
    }

    @Test
    void perClubMissingSeedSurfacesEntityAtClub() {
        Inputs inputs = new Inputs(
                satisfied().knownMapperEntities(),
                satisfied().systemGlobalEntities(),
                TWO_CLUBS,
                Map.of(EntityType.USER, Set.of("club-1")),
                satisfied().systemGlobalSeededCounts(),
                satisfied().manifestPolicyEntities(),
                satisfied().unmappedRegistryTables(),
                satisfied().unmappedTableRowCountsPostIngest());
        assertThat(ParityCoverageGate.diagnose(inputs))
                .contains("seed gap: USER@club-2");
    }

    @Test
    void systemGlobalMapperWithNoRowsSurfacesEntityWithoutClub() {
        Inputs inputs = new Inputs(
                satisfied().knownMapperEntities(),
                satisfied().systemGlobalEntities(),
                TWO_CLUBS,
                satisfied().seededClubsByEntity(),
                Map.of(EntityType.COUNTRY, 0L),
                satisfied().manifestPolicyEntities(),
                satisfied().unmappedRegistryTables(),
                satisfied().unmappedTableRowCountsPostIngest());
        assertThat(ParityCoverageGate.diagnose(inputs)).contains("seed gap: COUNTRY");
    }

    @Test
    void manifestPolicyKeyMissingFromKnownMappersIsReported() {
        Inputs inputs = new Inputs(
                satisfied().knownMapperEntities(),
                satisfied().systemGlobalEntities(),
                TWO_CLUBS,
                satisfied().seededClubsByEntity(),
                satisfied().systemGlobalSeededCounts(),
                Set.of(EntityType.COUNTRY, EntityType.USER, EntityType.FLIGHT),
                satisfied().unmappedRegistryTables(),
                satisfied().unmappedTableRowCountsPostIngest());
        assertThat(ParityCoverageGate.diagnose(inputs))
                .anyMatch(line -> line.contains("FLIGHT") && line.contains("mapper"));
    }

    @Test
    void unmappedRegistryTableWithRowsPostIngestIsReported() {
        Inputs inputs = new Inputs(
                satisfied().knownMapperEntities(),
                satisfied().systemGlobalEntities(),
                TWO_CLUBS,
                satisfied().seededClubsByEntity(),
                satisfied().systemGlobalSeededCounts(),
                satisfied().manifestPolicyEntities(),
                Set.of("Settings"),
                Map.of("Settings", 3L));
        assertThat(ParityCoverageGate.diagnose(inputs))
                .anyMatch(line -> line.contains("Settings") && line.contains("3"));
    }

    @Test
    void diagnosticsAreDeterministicallyOrdered() {
        Inputs inputs = new Inputs(
                satisfied().knownMapperEntities(),
                satisfied().systemGlobalEntities(),
                TWO_CLUBS,
                Map.of(EntityType.USER, Set.of()),
                Map.of(EntityType.COUNTRY, 0L),
                satisfied().manifestPolicyEntities(),
                satisfied().unmappedRegistryTables(),
                satisfied().unmappedTableRowCountsPostIngest());
        List<String> first = ParityCoverageGate.diagnose(inputs);
        List<String> second = ParityCoverageGate.diagnose(inputs);
        assertThat(first).isEqualTo(second).isNotEmpty();
    }
}

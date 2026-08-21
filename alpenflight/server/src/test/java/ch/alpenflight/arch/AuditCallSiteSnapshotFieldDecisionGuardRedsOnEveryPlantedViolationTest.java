package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.arch.AuditCallSiteSnapshotFieldDecisionGuard.UndecidedSnapshotFields;
import ch.alpenflight.arch.AuditRedactionCoverageTest.RedactionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditCallSiteSnapshotFieldDecisionGuardRedsOnEveryPlantedViolationTest {

    private static final String PLANTED_PACKAGE = "plantedauditcallsites";

    private static final String DECIDED_ENTITY = "PlantedDecidedEntity";
    private static final String UNDECIDED_ENTITY = "PlantedEntityWithNoPolicyEntry";
    private static final String DENY_ALL_ENTITY = "PlantedDenyAllEntity";

    private static final RedactionPolicy POLICY = new RedactionPolicy(
            Set.of(DENY_ALL_ENTITY),
            Map.of(DECIDED_ENTITY, Set.of("callSign", "seatCount")));

    @TempDir
    Path plantedSources;

    @Test
    void input_class_created_snapshot_carrying_a_field_the_entity_type_does_not_decide()
            throws Exception {
        assertPlantedCallSiteIsCaught(
                "AuditedTarget.created(\"" + DECIDED_ENTITY + "\", id, snapshot)",
                UNDECIDED_ENTITY + "Snapshot",
                DECIDED_ENTITY,
                List.of("cargoWeight"));
    }

    @Test
    void input_class_updated_before_snapshot_carrying_an_undecided_field() throws Exception {
        assertPlantedCallSiteIsCaught(
                "AuditedTarget.updated(\"" + DECIDED_ENTITY + "\", id, snapshot, decided)",
                UNDECIDED_ENTITY + "Snapshot",
                DECIDED_ENTITY,
                List.of("cargoWeight"));
    }

    @Test
    void input_class_updated_after_snapshot_carrying_an_undecided_field() throws Exception {
        assertPlantedCallSiteIsCaught(
                "AuditedTarget.updated(\"" + DECIDED_ENTITY + "\", id, decided, snapshot)",
                UNDECIDED_ENTITY + "Snapshot",
                DECIDED_ENTITY,
                List.of("cargoWeight"));
    }

    @Test
    void input_class_deleted_before_snapshot_carrying_an_undecided_field() throws Exception {
        assertPlantedCallSiteIsCaught(
                "AuditedTarget.deleted(\"" + DECIDED_ENTITY + "\", id, snapshot)",
                UNDECIDED_ENTITY + "Snapshot",
                DECIDED_ENTITY,
                List.of("cargoWeight"));
    }

    @Test
    void input_class_entity_type_that_has_no_policy_entry_at_all() throws Exception {
        assertPlantedCallSiteIsCaught(
                "AuditedTarget.created(\"" + UNDECIDED_ENTITY + "\", id, decided)",
                DECIDED_ENTITY + "Snapshot",
                UNDECIDED_ENTITY,
                List.of("callSign", "seatCount"));
    }

    @Test
    void input_class_entity_type_read_from_a_compile_time_constant_field() throws Exception {
        List<UndecidedSnapshotFields> found = scanPlanted(
                "AuditedTarget.created(ENTITY_TYPE_CONSTANT, id, snapshot)");
        assertThat(found).extracting(UndecidedSnapshotFields::entityType)
                .as("the guard must read through a static final String constant, not give up on it")
                .containsExactly(DECIDED_ENTITY);
    }

    @Test
    void input_class_entity_type_derived_from_a_class_literal_simple_name() throws Exception {
        List<UndecidedSnapshotFields> found = scanPlanted(
                "AuditedTarget.created(" + UNDECIDED_ENTITY + "Snapshot.class.getSimpleName(),"
                        + " id, snapshot)");
        assertThat(found).extracting(UndecidedSnapshotFields::entityType)
                .as("the guard must read X.class.getSimpleName() as the entity type X")
                .containsExactly(UNDECIDED_ENTITY + "Snapshot");
    }

    @Test
    void input_class_entity_type_that_no_static_reading_can_resolve() throws Exception {
        List<UndecidedSnapshotFields> found = scanPlanted(
                "AuditedTarget.created(entityTypeChosenAtRuntime(), id, decided)");
        assertThat(found).extracting(UndecidedSnapshotFields::entityType)
                .as("an entity type the guard cannot read is never assumed correct")
                .containsExactly(AuditCallSiteSnapshotFieldDecisionGuard.UNRESOLVABLE_ENTITY_TYPE);
    }

    @Test
    void a_fully_decided_call_site_stays_green() throws Exception {
        assertThat(scanPlanted("AuditedTarget.created(\"" + DECIDED_ENTITY + "\", id, decided)"))
                .isEmpty();
    }

    @Test
    void a_deny_all_entity_type_stays_green_because_an_empty_row_is_the_decision() throws Exception {
        assertThat(scanPlanted("AuditedTarget.created(\"" + DENY_ALL_ENTITY + "\", id, snapshot)"))
                .isEmpty();
    }

    private void assertPlantedCallSiteIsCaught(String plantedCall,
                                               String expectedSnapshotSimpleName,
                                               String expectedEntityType,
                                               List<String> expectedUndecidedFields)
            throws Exception {
        List<UndecidedSnapshotFields> found = scanPlanted(plantedCall);
        assertThat(found).hasSize(1);
        UndecidedSnapshotFields violation = found.get(0);
        assertThat(violation.entityType()).isEqualTo(expectedEntityType);
        assertThat(violation.snapshotType()).endsWith("$" + expectedSnapshotSimpleName);
        assertThat(violation.undecidedFields())
                .containsExactlyElementsOf(expectedUndecidedFields);
        assertThat(AuditCallSiteSnapshotFieldDecisionGuard.renderRefusal(found, List.of()))
                .contains(expectedEntityType)
                .contains(AuditCallSiteSnapshotFieldDecisionGuard
                        .RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);
    }

    private List<UndecidedSnapshotFields> scanPlanted(String plantedCall) throws Exception {
        String classpath = System.getProperty("java.class.path");
        Path packageDir = Files.createDirectories(plantedSources.resolve(PLANTED_PACKAGE));
        Path source = packageDir.resolve("PlantedAuditCallSite.java");
        Files.writeString(source, plantedSourceWith(plantedCall));
        compilePlantedSnapshots(source, classpath);
        return AuditCallSiteSnapshotFieldDecisionGuard.scan(
                List.of(source), classpath, POLICY, plantedSnapshotLoader());
    }

    private void compilePlantedSnapshots(Path source, String classpath) {
        java.io.StringWriter javacOutput = new java.io.StringWriter();
        int exit = javax.tools.ToolProvider.getSystemJavaCompiler().run(
                null, null, null,
                "-proc:none", "-nowarn",
                "-classpath", classpath,
                "-d", plantedSources.toString(),
                source.toString());
        assertThat(exit).as("the planted source must compile, otherwise the red proves "
                + "nothing about the guard: %s", javacOutput).isZero();
    }

    private ClassLoader plantedSnapshotLoader() throws Exception {
        return new java.net.URLClassLoader(
                new java.net.URL[] {plantedSources.toUri().toURL()},
                getClass().getClassLoader());
    }

    private static String plantedSourceWith(String plantedCall) {
        return """
                package %s;

                import ch.alpenflight.audit.domain.AuditedTarget;
                import java.util.UUID;

                public final class PlantedAuditCallSite {

                    static final String ENTITY_TYPE_CONSTANT = "%s";

                    public record %sSnapshot(String callSign, int seatCount) {}

                    public record %sSnapshot(int cargoWeight) {}

                    public static AuditedTarget plant(UUID id,
                                                      %sSnapshot decided,
                                                      %sSnapshot snapshot) {
                        return %s;
                    }

                    static String entityTypeChosenAtRuntime() {
                        return String.valueOf(System.nanoTime());
                    }
                }
                """.formatted(PLANTED_PACKAGE, DECIDED_ENTITY,
                DECIDED_ENTITY, UNDECIDED_ENTITY,
                DECIDED_ENTITY, UNDECIDED_ENTITY,
                plantedCall);
    }
}

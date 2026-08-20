package ch.alpenflight.audit.application;

import static ch.alpenflight.audit.application.AuditRedactionConfigStartupGuardTest.guardFor;
import static ch.alpenflight.audit.application.AuditRedactionConfigStartupGuardTest.properties;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.audit.application.AuditRedactionConfigStartupGuardTest.SnapshotAfterTheRename;
import ch.alpenflight.audit.application.AuditRedactionConfigStartupGuardTest.SnapshotBeforeTheRename;
import ch.alpenflight.audit.application.AuditRedactionConfigStartupGuardTest.SnapshotWithANestedHolder;
import ch.alpenflight.audit.application.AuditRedactionConfigStartupGuardTest.SnapshotWithAConstantThatIsNeverSerialized;
import ch.alpenflight.audit.application.AuditRedactionProperties.EntityPolicy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuditRedactionConfigGuardRedsOnEveryPlantedViolationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void assertTheGuardRedsOn(AuditRedactionProperties planted,
                                             String expectedConfigKey,
                                             String expectedReason) {
        assertThat(guardFor(planted).violations())
                .singleElement().asString()
                .contains(expectedConfigKey)
                .contains(expectedReason);
    }

    @Test
    void input_class_misspelled_field_in_an_allow_list() {
        assertTheGuardRedsOn(
                properties(Map.of(SnapshotBeforeTheRename.class.getSimpleName(),
                        EntityPolicy.allowing(List.of("locationName", "icaoCod")))),
                "audit.redaction.entities.SnapshotBeforeTheRename.allow[1]",
                "\"icaoCod\" is not an instance field");
    }

    @Test
    void input_class_field_that_existed_and_was_renamed() {
        AuditRedactionProperties planted = properties(Map.of(
                SnapshotAfterTheRename.class.getSimpleName(),
                EntityPolicy.allowing(List.of("locationName", "icaoCode"))));

        String snapshotTheCodeWithoutTheGuardWouldHaveWritten = new PiiRedactor(planted, JSON)
                .serialize(SnapshotAfterTheRename.class.getSimpleName(),
                        new SnapshotAfterTheRename());

        assertThat(snapshotTheCodeWithoutTheGuardWouldHaveWritten)
                .as("scoring the code without the guard: the rename empties the audit trail for the "
                        + "renamed field and nothing fails")
                .contains("\"name\":\"" + PiiRedactor.REDACTED_SENTINEL + "\"")
                .doesNotContain("Bern-Belp");

        assertTheGuardRedsOn(planted,
                "audit.redaction.entities.SnapshotAfterTheRename.allow[0]",
                "\"locationName\" is not an instance field");
    }

    @Test
    void input_class_entity_name_that_resolves_to_no_class() {
        assertTheGuardRedsOn(
                properties(Map.of("SnapshotThatNoClassDeclares",
                        EntityPolicy.allowing(List.of("icaoCode")))),
                "audit.redaction.entities.SnapshotThatNoClassDeclares",
                "resolves to no class under ch.alpenflight");
    }

    @Test
    void input_class_misspelled_entity_name_in_the_deny_direction() {
        assertTheGuardRedsOn(
                new AuditRedactionProperties(Map.of(),
                        List.of("SnapshotBeforeTheRename", "Persn")),
                "audit.redaction.deny-all[1]",
                "resolves to no class under ch.alpenflight");
    }

    @Test
    void input_class_dotted_nested_field_path() {
        assertTheGuardRedsOn(
                properties(Map.of(SnapshotWithANestedHolder.class.getSimpleName(),
                        EntityPolicy.allowing(List.of("holder.innerField")))),
                "audit.redaction.entities.SnapshotWithANestedHolder.allow[0]",
                "is a dotted path");
    }

    @Test
    void input_class_static_constant_the_redactor_never_serializes() {
        assertTheGuardRedsOn(
                properties(Map.of(SnapshotWithAConstantThatIsNeverSerialized.class.getSimpleName(),
                        EntityPolicy.allowing(List.of("realField", "MAX_NAME_LENGTH")))),
                "allow[1]",
                "\"MAX_NAME_LENGTH\" is not an instance field");
    }

    @Test
    void input_class_blank_field_name() {
        assertTheGuardRedsOn(
                properties(Map.of(SnapshotBeforeTheRename.class.getSimpleName(),
                        EntityPolicy.allowing(List.of("  ")))),
                "allow[0]",
                "a blank field name is configured");
    }

    @Test
    void input_class_entity_name_that_matches_more_than_one_class() {
        assertTheGuardRedsOn(
                properties(Map.of("AmbiguousBySimpleName",
                        EntityPolicy.allowing(List.of("onlyOnTheOuterOne")))),
                "audit.redaction.entities.AmbiguousBySimpleName",
                "is ambiguous");
    }

    @Test
    void input_class_declared_snapshot_type_that_does_not_load() {
        assertTheGuardRedsOn(
                properties(Map.of("PersonLicences",
                        new EntityPolicy(List.of("licenceNumber"),
                                List.of("ch.alpenflight.persons.application.SelfLicencesViewTypo")))),
                "snapshot-types",
                "is not a loadable class");
    }

    @Test
    void input_class_entity_declared_in_the_configuration_but_dropped_by_the_binding()
            throws IOException {
        AuditRedactionProperties bindingThatSilentlyDroppedEveryEntity =
                new AuditRedactionProperties(Map.of(), List.of());

        assertThat(guardFor(bindingThatSilentlyDroppedEveryEntity,
                ShippedRedactionPolicy.environment()).violations())
                .as("a value-object binding failure empties every allow-list without an error, so "
                        + "the guard compares the declared keys against the bound ones")
                .isNotEmpty()
                .anySatisfy(violation -> assertThat(violation)
                        .contains("audit.redaction.entities.Location")
                        .contains("the bound policy has no entry for it"));
    }
}

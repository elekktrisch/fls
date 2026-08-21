package ch.alpenflight.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.audit.application.AuditRedactionProperties.EntityPolicy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class AuditRedactionConfigStartupGuardTest {

    static final String LOCATION_SNAPSHOT_TYPE_PROPERTY =
            "audit.redaction.entities.Location.snapshot-types[0]="
                    + "ch.alpenflight.locations.domain.Location";

    static class SnapshotBeforeTheRename {
        String locationName = "Bern-Belp";
        String icaoCode = "LSZB";
    }

    static class SnapshotAfterTheRename {
        String name = "Bern-Belp";
        String icaoCode = "LSZB";
    }

    static class SnapshotSuperclassDeclaringATenantField {
        String tenantClubId = "clb-1";
    }

    static class SnapshotInheritingTheTenantField extends SnapshotSuperclassDeclaringATenantField {
        String ownField = "own";
    }

    static class SnapshotWithAConstantThatIsNeverSerialized {
        static final String MAX_NAME_LENGTH = "100";
        String realField = "real";
    }

    static class SnapshotWithANestedHolder {
        NestedHolder holder = new NestedHolder();
    }

    static class NestedHolder {
        String innerField = "inner";
    }

    static class AmbiguousBySimpleName {
        String onlyOnTheOuterOne = "outer";
    }

    static class Nesting {
        static class AmbiguousBySimpleName {
            String onlyOnTheNestedOne = "nested";
        }
    }

    @Test
    void the_shipped_redaction_configuration_resolves_completely() throws IOException {
        AuditRedactionProperties shipped = ShippedRedactionPolicy.properties();

        assertThat(shipped.entities())
                .as("the binding must carry the real policy, else this assertion passes vacuously")
                .hasSizeGreaterThan(25);
        assertThat(shipped.denyAll()).contains("Person");
        assertThat(shipped.entities().get("Location").allow()).contains("locationName");

        assertThat(guardFor(shipped).violations())
                .as("every name in the shipped audit.redaction config must resolve to a real class "
                        + "and a real instance field")
                .isEmpty();
    }

    @Test
    void an_inherited_field_resolves_on_the_superclass_and_is_accepted() {
        AuditRedactionProperties planted = properties(Map.of(
                SnapshotInheritingTheTenantField.class.getSimpleName(),
                EntityPolicy.allowing(List.of("ownField", "tenantClubId"))));

        assertThat(guardFor(planted).violations())
                .as("the redactor serializes superclass fields, so the guard must resolve them too")
                .isEmpty();
    }

    @Test
    void an_explicit_snapshot_type_disambiguates_an_ambiguous_name_and_is_accepted() {
        AuditRedactionProperties planted = properties(Map.of(
                "AmbiguousBySimpleName",
                new EntityPolicy(List.of("onlyOnTheNestedOne"),
                        List.of(Nesting.AmbiguousBySimpleName.class.getName()))));

        assertThat(guardFor(planted).violations()).isEmpty();
    }

    @Test
    void the_application_context_starts_when_every_configured_name_resolves() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuditConfig.class, AuditRedactionConfigStartupGuard.class)
                .withPropertyValues(
                        LOCATION_SNAPSHOT_TYPE_PROPERTY,
                        "audit.redaction.entities.Location.allow[0]=locationName",
                        "audit.redaction.entities.Location.allow[1]=locationShortName")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void the_application_context_refuses_to_start_on_a_field_name_that_resolves_to_nothing() {
        new ApplicationContextRunner()
                .withUserConfiguration(AuditConfig.class, AuditRedactionConfigStartupGuard.class)
                .withPropertyValues(
                        LOCATION_SNAPSHOT_TYPE_PROPERTY,
                        "audit.redaction.entities.Location.allow[0]=locationName",
                        "audit.redaction.entities.Location.allow[1]=locationShortNam")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("audit.redaction.entities.Location.allow[1]")
                        .hasMessageContaining("refuses to start"));
    }

    @Test
    void the_refusal_message_states_the_residual_limit() {
        assertThat(AuditRedactionConfigStartupGuard.refusalMessage(List.of("planted")))
                .contains("refuses to start")
                .contains(AuditRedactionConfigStartupGuard.RESIDUAL_LIMIT_THE_GUARD_DOES_NOT_COVER);
    }

    static AuditRedactionConfigStartupGuard guardFor(AuditRedactionProperties properties) {
        return guardFor(properties, new StandardEnvironment());
    }

    static AuditRedactionConfigStartupGuard guardFor(AuditRedactionProperties properties,
                                                     Environment environment) {
        return new AuditRedactionConfigStartupGuard(properties, environment,
                new PathMatchingResourcePatternResolver(
                        AuditRedactionConfigStartupGuardTest.class.getClassLoader()));
    }

    static AuditRedactionProperties properties(Map<String, EntityPolicy> entities) {
        return new AuditRedactionProperties(entities, List.of());
    }
}

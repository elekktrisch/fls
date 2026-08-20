package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import clubprovisioningenvelopeplants.ClubProvisioningEnvelopePlants;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClubProvisioningEnvelopeDeploymentScopeGuardRedsOnEveryPlantedViolationTest {

    private static final JavaClasses PLANTED_ENVELOPES =
            new ClassFileImporter().importPackages("clubprovisioningenvelopeplants");

    private static void assertTheGuardRedsOn(Class<?> plantedEnvelope,
                                             String expectedComponentPath,
                                             String expectedReason) {
        assertThatThrownBy(() -> ClubProvisioningEnvelopeDeploymentScopeGuardTest
                .clubProvisioningEnvelopeTypesCarryNoDeploymentScopedComponent(
                        Set.of(plantedEnvelope.getName()))
                .check(PLANTED_ENVELOPES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(expectedComponentPath)
                .hasMessageContaining(expectedReason);
    }

    @Test
    void input_class_record_component_named_for_the_deployment() {
        assertTheGuardRedsOn(
                ClubProvisioningEnvelopePlants.PlantedClubSpecWithADeploymentIdRecordComponent.class,
                "PlantedClubSpecWithADeploymentIdRecordComponent.deploymentId",
                "names the deployment scope");
    }

    @Test
    void input_class_private_field_on_a_specification_that_is_a_class_not_a_record() {
        assertTheGuardRedsOn(
                ClubProvisioningEnvelopePlants.PlantedClubSpecClassWithADeploymentIdField.class,
                "PlantedClubSpecClassWithADeploymentIdField.deploymentId",
                "names the deployment scope");
    }

    @Test
    void input_class_nested_carrier_whose_own_name_hides_the_deployment() {
        assertTheGuardRedsOn(
                ClubProvisioningEnvelopePlants.PlantedClubSpecWithANestedTenantHome.class,
                "PlantedClubSpecWithANestedTenantHome.home.deploymentId",
                "names the deployment scope");
    }

    @Test
    void input_class_innocently_named_component_bound_to_the_deployment_column() {
        assertTheGuardRedsOn(
                ClubProvisioningEnvelopePlants
                        .PlantedClubSpecBindingTheDeploymentColumnUnderAnInnocentName.class,
                "PlantedClubSpecBindingTheDeploymentColumnUnderAnInnocentName.owner",
                "binds the deployment scope under the serialized name \"deployment_id\"");
    }

    @Test
    void input_class_club_declaration_reached_only_through_a_generic_list_component() {
        assertTheGuardRedsOn(
                ClubProvisioningEnvelopePlants.PlantedManifestWhoseClubListCarriesTheDeployment.class,
                "PlantedManifestWhoseClubListCarriesTheDeployment.clubs.deploymentId",
                "names the deployment scope");
    }

    @Test
    void a_deployment_name_is_a_label_not_a_tenant_selector_so_the_guard_stays_green_on_it() {
        ClubProvisioningEnvelopeDeploymentScopeGuardTest
                .clubProvisioningEnvelopeTypesCarryNoDeploymentScopedComponent(
                        Set.of(ManifestCarryingOnlyADeploymentName.class.getName()))
                .check(new ClassFileImporter()
                        .importClasses(ManifestCarryingOnlyADeploymentName.class));
    }

    record ManifestCarryingOnlyADeploymentName(String deploymentName) {
    }
}

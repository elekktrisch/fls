package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import packagedartifactdependencyshapeplants.PackagedArtifactDependencyShapePlants;

class PackagedArtifactDependencyShapeGuardRedsOnEveryPlantedViolationTest {

    private static JavaClasses imported(Class<?> plant) {
        return new ClassFileImporter().importClasses(plant);
    }

    private static void assertTheRuleRedsOn(Class<?> plant, String expectedShapeDescription) {
        assertThatThrownBy(() -> PackagedArtifactDependencyShapeGuardTest
                .noProductionClassInjectsARestClientBuilder()
                .check(imported(plant)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("injects a RestClient.Builder")
                .hasMessageContaining(expectedShapeDescription)
                .hasMessageContaining(PackagedArtifactDependencyShapeGuardTest
                        .WHY_NO_PRODUCTION_CLASS_MAY_INJECT_THE_REST_CLIENT_BUILDER)
                .hasMessageContaining(PackagedArtifactDependencyShapeGuardTest
                        .RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);
    }

    private static void assertTheRuleStaysGreenOn(Class<?> plant) {
        assertThatCode(() -> PackagedArtifactDependencyShapeGuardTest
                .noProductionClassInjectsARestClientBuilder()
                .check(imported(plant)))
                .doesNotThrowAnyException();
    }

    @Test
    void input_class_builder_as_a_constructor_parameter() {
        assertTheRuleRedsOn(
                PackagedArtifactDependencyShapePlants.PlantedBuilderAsAConstructorParameter.class,
                "a constructor parameter of");
    }

    @Test
    void input_class_builder_as_an_autowired_field() {
        assertTheRuleRedsOn(
                PackagedArtifactDependencyShapePlants.PlantedBuilderAsAnAutowiredField.class,
                "into the field restClientBuilder");
    }

    @Test
    void input_class_builder_as_a_jakarta_resource_field() {
        assertTheRuleRedsOn(
                PackagedArtifactDependencyShapePlants.PlantedBuilderAsAJakartaResourceField.class,
                "into the field restClientBuilder");
    }

    @Test
    void input_class_builder_through_an_autowired_setter() {
        assertTheRuleRedsOn(
                PackagedArtifactDependencyShapePlants.PlantedBuilderThroughAnAutowiredSetter.class,
                "an injected parameter of the method setRestClientBuilder");
    }

    @Test
    void input_class_builder_as_a_bean_method_parameter() {
        assertTheRuleRedsOn(
                PackagedArtifactDependencyShapePlants.PlantedBuilderAsABeanMethodParameter.class,
                "an injected parameter of the method plantedRestClient");
    }

    @Test
    void input_class_builder_taken_indirectly_inside_an_object_provider() {
        assertTheRuleRedsOn(
                PackagedArtifactDependencyShapePlants.PlantedBuilderInsideAnObjectProvider.class,
                "org.springframework.beans.factory.ObjectProvider"
                        + "<org.springframework.web.client.RestClient$Builder>");
    }

    @Test
    void a_client_built_in_place_from_the_static_factory_stays_green() {
        assertTheRuleStaysGreenOn(PackagedArtifactDependencyShapePlants
                .PlantedClientBuiltInPlaceFromTheStaticFactory.class);
    }

    @Test
    void a_builder_held_only_as_a_local_variable_stays_green() {
        assertTheRuleStaysGreenOn(PackagedArtifactDependencyShapePlants
                .PlantedBuilderAsALocalVariableInsideAMethod.class);
    }

    @Test
    void a_builder_returned_by_a_method_rather_than_injected_stays_green() {
        assertTheRuleStaysGreenOn(PackagedArtifactDependencyShapePlants
                .PlantedBuilderAsAMethodReturnTypeNotAParameter.class);
    }
}

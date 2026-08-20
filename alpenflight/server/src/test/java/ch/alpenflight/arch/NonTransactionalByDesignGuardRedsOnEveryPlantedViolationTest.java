package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.arch.NonTransactionalByDesignGuardTest.MethodPinnedNonTransactional;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import nontransactionalbydesignplants.NonTransactionalByDesignPlants;
import org.junit.jupiter.api.Test;

class NonTransactionalByDesignGuardRedsOnEveryPlantedViolationTest {

    private static final String WHY =
            NonTransactionalByDesignGuardTest.WHY_LATEST_FOR_CALLER_MUST_STAY_NON_TRANSACTIONAL;

    private static Map<MethodPinnedNonTransactional, String> pinning(Class<?> plantedOwner) {
        return Map.of(new MethodPinnedNonTransactional(plantedOwner.getName(),
                NonTransactionalByDesignPlants.PINNED_METHOD_NAME), WHY);
    }

    private static JavaClasses imported(Class<?>... plants) {
        return new ClassFileImporter().importClasses(plants);
    }

    private static void assertTheAnnotationRuleRedsOn(Class<?> plantedOwner,
                                                      String expectedAnnotationRendering,
                                                      String expectedCarrier) {
        assertThatThrownBy(() -> NonTransactionalByDesignGuardTest
                .methodsPinnedNonTransactionalCarryNoTransactionalAnnotation(pinning(plantedOwner))
                .check(imported(plantedOwner)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(expectedAnnotationRendering)
                .hasMessageContaining(expectedCarrier)
                .hasMessageContaining(WHY)
                .hasMessageContaining(
                        NonTransactionalByDesignGuardTest.RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);
    }

    @Test
    void input_class_method_level_transactional() {
        assertTheAnnotationRuleRedsOn(
                NonTransactionalByDesignPlants.PlantedPinnedMethodWithAMethodLevelTransactional.class,
                "@Transactional",
                "on the method itself");
    }

    @Test
    void input_class_class_level_transactional_the_method_inherits() {
        assertTheAnnotationRuleRedsOn(
                NonTransactionalByDesignPlants
                        .PlantedPinnedMethodInheritingAClassLevelTransactional.class,
                "@Transactional",
                "inherits @Transactional");
    }

    @Test
    void input_class_transactional_with_a_non_default_propagation() {
        assertTheAnnotationRuleRedsOn(
                NonTransactionalByDesignPlants.PlantedPinnedMethodWithANonDefaultPropagation.class,
                "REQUIRES_NEW",
                "on the method itself");
    }

    @Test
    void input_class_spring_meta_annotation_composed_over_transactional_on_the_method() {
        assertTheAnnotationRuleRedsOn(
                NonTransactionalByDesignPlants
                        .PlantedPinnedMethodWithAComposedTransactionAnnotation.class,
                "a meta-annotation composed over @Transactional",
                "on the method itself");
    }

    @Test
    void input_class_spring_meta_annotation_composed_over_transactional_on_the_class() {
        assertTheAnnotationRuleRedsOn(
                NonTransactionalByDesignPlants
                        .PlantedPinnedMethodInheritingAComposedTransactionAnnotation.class,
                "a meta-annotation composed over @Transactional",
                "from its declaring class");
    }

    @Test
    void input_class_the_jakarta_transactional_instead_of_the_spring_one() {
        assertTheAnnotationRuleRedsOn(
                NonTransactionalByDesignPlants.PlantedPinnedMethodWithTheJakartaTransactional.class,
                "@Transactional",
                "on the method itself");
    }

    @Test
    void input_class_transactional_caller_that_leaves_the_callee_unannotated() {
        Class<?> callee = NonTransactionalByDesignPlants.PlantedPinnedCallee.class;
        Class<?> caller =
                NonTransactionalByDesignPlants.PlantedTransactionalCallerOfThePinnedMethod.class;

        assertThatThrownBy(() -> NonTransactionalByDesignGuardTest
                .noTransactionBearingClassCallsAMethodPinnedNonTransactional(pinning(callee))
                .check(imported(callee, caller)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("opens or joins a transaction and calls")
                .hasMessageContaining(NonTransactionalByDesignPlants.PINNED_METHOD_NAME)
                .hasMessageContaining(WHY);
    }

    @Test
    void input_class_caller_wrapping_the_callee_in_a_transaction_template_lambda() {
        Class<?> callee = NonTransactionalByDesignPlants.PlantedPinnedCallee.class;
        Class<?> caller = NonTransactionalByDesignPlants
                .PlantedTransactionTemplateWrapperOfThePinnedMethod.class;

        assertThatThrownBy(() -> NonTransactionalByDesignGuardTest
                .noTransactionBearingClassCallsAMethodPinnedNonTransactional(pinning(callee))
                .check(imported(callee, caller)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("opens or joins a transaction and calls")
                .hasMessageContaining(WHY);
    }

    @Test
    void a_transactional_sibling_method_does_not_implicate_the_pinned_method() {
        Class<?> plant =
                NonTransactionalByDesignPlants.PlantedPinnedMethodBesideATransactionalSibling.class;

        assertThatCode(() -> NonTransactionalByDesignGuardTest
                .methodsPinnedNonTransactionalCarryNoTransactionalAnnotation(pinning(plant))
                .check(imported(plant)))
                .doesNotThrowAnyException();
    }

    @Test
    void a_caller_that_opens_no_transaction_may_call_the_pinned_method() {
        Class<?> callee = NonTransactionalByDesignPlants.PlantedPinnedCallee.class;
        Class<?> caller =
                NonTransactionalByDesignPlants.PlantedNonTransactionalCallerOfThePinnedMethod.class;

        assertThatCode(() -> NonTransactionalByDesignGuardTest
                .noTransactionBearingClassCallsAMethodPinnedNonTransactional(pinning(callee))
                .check(imported(callee, caller)))
                .doesNotThrowAnyException();
    }

    @Test
    void every_pinned_method_still_exists_so_a_rename_cannot_empty_the_guard() {
        NonTransactionalByDesignGuardTest.METHODS_PINNED_NON_TRANSACTIONAL.keySet()
                .forEach(pin -> {
                    Class<?> owner = loadedOwnerOf(pin);
                    assertThat(Arrays.stream(owner.getDeclaredMethods()).map(Method::getName))
                            .as("%s.%s is pinned non-transactional by design; a rename that leaves "
                                            + "the pin behind turns the guard into a no-op",
                                    pin.declaringClassFullName(), pin.methodName())
                            .contains(pin.methodName());
                });
    }

    private static Class<?> loadedOwnerOf(MethodPinnedNonTransactional pin) {
        try {
            return Class.forName(pin.declaringClassFullName());
        } catch (ClassNotFoundException notOnTheClasspath) {
            throw new AssertionError(pin.declaringClassFullName()
                    + " is pinned non-transactional by design but no longer exists", notOnTheClasspath);
        }
    }
}

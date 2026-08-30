package ch.alpenflight.buildgates;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoBinaryFloatInMoneyTypeGateTest {

    private static final String[] MONEY_PACKAGE_INFIXES = {"..charging..", "..delivery..", "..invoice.."};

    private static final Set<Class<?>> BINARY_FLOATING_POINT_TYPES =
            Set.of(double.class, float.class, Double.class, Float.class, double[].class, float[].class);

    private static final ArchCondition<JavaField> HAVE_A_BINARY_FLOATING_POINT_TYPE = new ArchCondition<>(
            "have a binary floating-point type (double, float, Double, Float, double[], or float[])") {
        @Override
        public void check(JavaField field, ConditionEvents events) {
            boolean isBinaryFloat = BINARY_FLOATING_POINT_TYPES.stream()
                    .anyMatch(type -> field.getRawType().isEquivalentTo(type));
            if (isBinaryFloat) {
                events.add(SimpleConditionEvent.violated(
                        field, field.getFullName() + " has raw type " + field.getRawType().getName()));
            }
        }
    };

    @Test
    void productionMoneyTypesCarryNoBinaryFloatingPointField() {
        JavaClasses classes = new ClassFileImporter().importPackages(
                "ch.alpenflight.platform", "ch.alpenflight.core", "ch.alpenflight.modulesopen", "ch.alpenflight.modulespro");

        assertThatNoException()
                .as("Gate 5 (no binary float in money type)")
                .isThrownBy(() -> rule().check(classes));
    }

    @Test
    void fixtureBinaryFloatMoneyFieldIsCaught() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("ch.alpenflight.buildgates.nobinaryfloatgate.fixtures.charging");

        assertThatThrownBy(() -> rule().check(classes)).hasMessageContaining("Gate 5");
    }

    @Test
    void fixtureBoxedBinaryFloatMoneyFieldIsCaught() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("ch.alpenflight.buildgates.nobinaryfloatgate.fixtures.invoice");

        assertThatThrownBy(() -> rule().check(classes)).hasMessageContaining("Gate 5");
    }

    private static ArchRule rule() {
        return fields()
                .that()
                .areDeclaredInClassesThat(resideInAnyPackage(MONEY_PACKAGE_INFIXES))
                .should(HAVE_A_BINARY_FLOATING_POINT_TYPE)
                .as("Gate 5 (no binary float in money type): no double/float field in a class whose package "
                        + "name contains charging, delivery, or invoice")
                .allowEmptyShould(true);
    }
}

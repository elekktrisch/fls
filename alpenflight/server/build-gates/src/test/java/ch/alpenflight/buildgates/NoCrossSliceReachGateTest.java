package ch.alpenflight.buildgates;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.buildgates.support.Slices;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoCrossSliceReachGateTest {

    private static final Set<String> HIDDEN_SLICE_PACKAGES = Set.of("domain", "application", "infra");

    @Test
    void productionCodeHasNoCrossSliceReach() {
        JavaClasses classes = new ClassFileImporter().importPackages(Slices.PRODUCTION_MODULE_ROOTS);

        List<String> violations = violationsOf(classes, Slices.PRODUCTION_MODULE_ROOTS);

        assertThat(violations).as("Gate 6 (no cross-slice reach)").isEmpty();
    }

    @Test
    void fixtureCrossSliceReachIsCaught() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("ch.alpenflight.buildgates.nocrossslicereachgate.fixtures");

        List<String> violations =
                violationsOf(classes, "ch.alpenflight.buildgates.nocrossslicereachgate.fixtures");

        assertThat(violations)
                .as("Gate 6 (no cross-slice reach) must catch a class outside a slice depending on that "
                        + "slice's domain/application/infra")
                .isNotEmpty();
        assertThat(violations.get(0)).contains("Gate 6");
    }

    @Test
    void fixtureInSliceReachToItsOwnHiddenPackageIsNotFlagged() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("ch.alpenflight.buildgates.nocrossslicereachgate.fixtures");

        List<String> violations =
                violationsOf(classes, "ch.alpenflight.buildgates.nocrossslicereachgate.fixtures");

        assertThat(violations)
                .as("Gate 6 (no cross-slice reach) must not flag a class inside its own slice reaching "
                        + "that slice's domain/application/infra")
                .noneMatch(violation -> violation.contains("InsiderService"));
    }

    private static List<String> violationsOf(JavaClasses classes, String... moduleRootPackages) {
        List<String> violations = new ArrayList<>();
        for (var entry : Slices.childPackagesAcrossModuleRoots(classes, moduleRootPackages)) {
            String slice = entry.getKey();
            for (String hidden : HIDDEN_SLICE_PACKAGES) {
                if (!entry.getValue().contains(hidden)) {
                    continue;
                }
                String hiddenPackage = slice + "." + hidden;
                ArchRule rule = noClasses()
                        .that(resideOutsideOfPackage(slice + ".."))
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage(hiddenPackage + "..")
                        .as("Gate 6 (no cross-slice reach): only classes inside " + slice
                                + " may depend on " + hiddenPackage);
                try {
                    rule.check(classes);
                } catch (AssertionError e) {
                    violations.add(e.getMessage());
                }
            }
        }
        return violations;
    }
}

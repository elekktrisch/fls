package ch.alpenflight.buildgates;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class CoreNeverReferencesProGateTest {

    @Test
    void productionCoreNeverReferencesModulesPro() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("ch.alpenflight.core", "ch.alpenflight.modulespro");

        assertThatNoException()
                .as("Gate 2 (core never references pro)")
                .isThrownBy(() -> rule("ch.alpenflight.core..", "ch.alpenflight.modulespro..").check(classes));
    }

    @Test
    void fixtureCoreReferencingProIsCaught() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("ch.alpenflight.buildgates.corevsprogate.fixtures");

        assertThatThrownBy(() -> rule(
                        "ch.alpenflight.buildgates.corevsprogate.fixtures.core..",
                        "ch.alpenflight.buildgates.corevsprogate.fixtures.pro..")
                .check(classes))
                .hasMessageContaining("Gate 2");
    }

    private static ArchRule rule(String fromPackage, String toPackage) {
        return noClasses()
                .that()
                .resideInAPackage(fromPackage)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(toPackage)
                .as("Gate 2 (core never references pro): no class in " + fromPackage
                        + " may depend on a class in " + toPackage);
    }
}

package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for {@link LayeringRulesTest}. Re-declares the same
 * rules and points them at the {@code archDemo} source set, which holds
 * three deliberate violations (one per rule). Each assertion expects the
 * rule to FAIL — if any rule silently passes here, someone weakened the
 * production rules in {@code LayeringRulesTest} and this regression
 * harness catches it.
 *
 * <p>Mirrors {@link ch.alpenflight.platform.id.FlsUuidV7Generator}'s
 * pattern of "intentionally bad input + assert failure" — see
 * {@code verifyNullAwayFailsOnViolation} for the established analog.
 *
 * <p>Run via {@code ./gradlew verifyArchUnitFailsOnViolation} — NOT part
 * of {@code check} / {@code build}; CI invokes it as a separate step.
 */
class LayeringRulesDemoTest {

    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                    .importPackages("ch.alpenflight");

    @Test
    void rule_1_catches_jackson_in_domain() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.stereotype..",
                        "org.springframework.boot..",
                        "org.springframework.context..",
                        "com.fasterxml.jackson..",
                        "jakarta.servlet..");

        assertThatThrownBy(() -> rule.check(CLASSES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JacksonLeak")
                .hasMessageContaining("com.fasterxml.jackson");
    }

    @Test
    void rule_2_catches_web_to_infra() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..web..")
                .should().dependOnClassesThat().resideInAPackage("..infra..");

        assertThatThrownBy(() -> rule.check(CLASSES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("InfraLeak")
                .hasMessageContaining("JpaClubRepository");
    }

    @Test
    void rule_3_catches_application_to_web() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..web..");

        assertThatThrownBy(() -> rule.check(CLASSES))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("WebLeak")
                .hasMessageContaining("ClubsController");
    }
}

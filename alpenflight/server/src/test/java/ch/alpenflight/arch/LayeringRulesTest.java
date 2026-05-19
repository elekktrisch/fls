package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit enforcement of the four-package per-module template from
 * ADR 0023. Spring Modulith ({@link ApplicationModulesTest}) verifies
 * inter-module boundaries (cross-module isolation); these rules verify
 * direction-of-dependency <em>inside</em> a module.
 *
 * <p>Pattern-based: rules match {@code ..<module>.domain..} etc., so a new
 * bounded-context module added later is enforced automatically with no
 * rule edits.
 */
@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class LayeringRulesTest {

    /**
     * {@code domain/} stays infra-free. Aggregates, value objects,
     * repository interfaces depend only on the JDK, JPA annotations
     * (deliberate concession from ADR 0023), JSpecify, and Spring
     * Modulith's events package (the only Spring import allowed because
     * {@code @DomainEvents} / {@code @ApplicationModuleListener} are the
     * canonical cross-aggregate channel from ADR 0018). The banned list
     * is intentionally broader than the ADR text (covers
     * {@code spring-boot} + {@code spring-context} too) so the rule also
     * blocks {@code @ConfigurationProperties} / {@code ApplicationContext}
     * sneaking into a value object.
     */
    @ArchTest
    static final ArchRule domain_stays_spring_web_free =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.web..",
                            "org.springframework.stereotype..",
                            "org.springframework.boot..",
                            "org.springframework.context..",
                            "com.fasterxml.jackson..",
                            "jakarta.servlet..")
                    .as("domain/ must stay free of Spring web/stereotypes/boot/context, Jackson, and the "
                            + "servlet API. Allowed exceptions: jakarta.persistence, jspecify, "
                            + "org.springframework.modulith.events. See ADR 0023.");

    /**
     * {@code web/} flows through the use-case layer; reaching past
     * {@code application/} into {@code infra/} is the leak this prevents.
     * Same-module {@code domain/} dependency is allowed (controllers /
     * advice catch domain exception types).
     */
    @ArchTest
    static final ArchRule web_does_not_reach_into_infra =
            noClasses()
                    .that().resideInAPackage("..web..")
                    .should().dependOnClassesThat().resideInAPackage("..infra..")
                    .as("web/ must not reach into infra/; controllers flow through application/. See ADR 0023.");

    /**
     * {@code application/} orchestrates the domain; the inbound web
     * adapter is below it in the dependency direction.
     */
    @ArchTest
    static final ArchRule application_does_not_depend_on_web =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..web..")
                    .as("application/ must not depend on web/. See ADR 0023.");

    /**
     * {@code application/} depends on the domain port, not the JPA
     * implementation. Without this rule the entire point of ADR 0023 —
     * making the port-not-adapter the only seam between use case and
     * persistence — falls back to code-review discipline.
     */
    @ArchTest
    static final ArchRule application_depends_on_domain_port_not_infra =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..infra..")
                    .as("application/ must depend on the domain port, not the Spring Data implementation "
                            + "in infra/. See ADR 0023.");
}

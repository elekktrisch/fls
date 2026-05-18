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
     * Rule 1 — {@code domain/} stays infra-free. Aggregates, value
     * objects, repository interfaces depend only on the JDK, JPA
     * annotations (deliberate concession from ADR 0023), JSpecify, and
     * Spring Modulith's events package (the only Spring import allowed
     * because {@code @DomainEvents} / {@code @ApplicationModuleListener}
     * are the canonical cross-aggregate channel from ADR 0018).
     */
    @ArchTest
    static final ArchRule domain_is_infra_free =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.web..",
                            "org.springframework.stereotype..",
                            "org.springframework.boot..",
                            "org.springframework.context..",
                            "com.fasterxml.jackson..",
                            "jakarta.servlet..")
                    .as("domain/ must not depend on Spring web/stereotypes, Jackson, or the servlet API "
                            + "(ADR 0023 rule 1; allowed: jakarta.persistence, jspecify, "
                            + "org.springframework.modulith.events)");

    /**
     * Rule 2 — {@code web/} sees only its own module's
     * {@code application/}. Reaching past application into {@code infra}
     * or {@code domain} is a violation.
     */
    @ArchTest
    static final ArchRule web_does_not_reach_into_infra =
            noClasses()
                    .that().resideInAPackage("..web..")
                    .should().dependOnClassesThat().resideInAPackage("..infra..")
                    .as("web/ must not reach into infra/ (ADR 0023 rule 2; "
                            + "controllers flow through application/)");

    /**
     * Rule 3 — {@code application/} does not depend on {@code web/}. Use
     * cases orchestrate domain; web is the inbound adapter.
     */
    @ArchTest
    static final ArchRule application_does_not_depend_on_web =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..web..")
                    .as("application/ must not depend on web/ (ADR 0023 rule 3)");
}

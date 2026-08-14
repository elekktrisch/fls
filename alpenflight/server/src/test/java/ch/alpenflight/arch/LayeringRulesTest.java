package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class LayeringRulesTest {

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

    @ArchTest
    static final ArchRule web_does_not_reach_into_infra =
            noClasses()
                    .that().resideInAPackage("..web..")
                    .should().dependOnClassesThat().resideInAPackage("..infra..")
                    .as("web/ must not reach into infra/; controllers flow through application/. See ADR 0023.");

    @ArchTest
    static final ArchRule application_does_not_depend_on_web =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..web..")
                    .as("application/ must not depend on web/. See ADR 0023.");

    @ArchTest
    static final ArchRule application_depends_on_domain_port_not_infra =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..infra..")
                    .as("application/ must depend on the domain port, not the Spring Data implementation "
                            + "in infra/. See ADR 0023.");
}

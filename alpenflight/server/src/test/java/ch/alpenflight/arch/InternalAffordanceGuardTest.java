package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.AlpenFlightApplication;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR 0029 guard. Every {@code @RestController} mapped under
 * {@code /api/v1/internal/} is a dev/test-only affordance compiled into the
 * production artifact and kept unreachable in production by runtime gates —
 * so it MUST carry {@code @Profile} naming only non-production profiles and
 * {@code @Hidden} (springdoc). A future affordance that forgets either, or
 * that names a production profile, fails the build here instead of silently
 * shipping reachable in production.
 */
class InternalAffordanceGuardTest {

    private static final String INTERNAL_PREFIX = "/api/v1/internal/";

    private static final String PRODUCTION_PROFILE = "prod";

    private static final Set<String> NON_PRODUCTION_PROFILES = Set.of("dev", "test", "showcase");

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importPackagesOf(AlpenFlightApplication.class);

    @Test
    void internal_affordance_controllers_are_profile_gated_and_hidden() {
        List<JavaClass> internalControllers = internalControllers();

        assertThat(internalControllers)
                .as("expected the two known /api/v1/internal/ affordance controllers to be discovered")
                .extracting(JavaClass::getSimpleName)
                .contains("InternalProvisioningController", "InternalPersonFlightTimeCreditSeedController");

        List<String> violations = new ArrayList<>();
        for (JavaClass controller : internalControllers) {
            if (!controller.isAnnotatedWith(Hidden.class)) {
                violations.add(controller.getName() + " is mapped under " + INTERNAL_PREFIX
                        + " but is not @Hidden (ADR 0029)");
            }
            if (!controller.isAnnotatedWith(Profile.class)) {
                violations.add(controller.getName() + " is mapped under " + INTERNAL_PREFIX
                        + " but carries no @Profile (ADR 0029)");
                continue;
            }
            List<String> profiles = profileValues(controller);
            for (String profile : profiles) {
                if (!NON_PRODUCTION_PROFILES.contains(profile)) {
                    violations.add(controller.getName() + " @Profile names \"" + profile
                            + "\" which is not a sanctioned non-production profile "
                            + NON_PRODUCTION_PROFILES + " (ADR 0029)");
                }
            }
            if (profiles.contains(PRODUCTION_PROFILE)) {
                violations.add(controller.getName() + " @Profile names the production profile \""
                        + PRODUCTION_PROFILE + "\" — an internal affordance must never run in production"
                        + " (ADR 0029)");
            }
        }

        assertThat(violations)
                .as("every /api/v1/internal/ controller must be @Profile(non-prod) + @Hidden (ADR 0029)")
                .isEmpty();
    }

    private List<JavaClass> internalControllers() {
        return classes.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class))
                .filter(c -> c.isAnnotatedWith(RequestMapping.class))
                .filter(c -> requestMappingPaths(c).stream().anyMatch(p -> p.startsWith(INTERNAL_PREFIX)))
                .toList();
    }

    private static List<String> requestMappingPaths(JavaClass controller) {
        RequestMapping mapping = controller.getAnnotationOfType(RequestMapping.class);
        List<String> paths = new ArrayList<>(Arrays.asList(mapping.value()));
        paths.addAll(Arrays.asList(mapping.path()));
        return paths;
    }

    private static List<String> profileValues(JavaClass controller) {
        return Arrays.asList(controller.getAnnotationOfType(Profile.class).value());
    }
}

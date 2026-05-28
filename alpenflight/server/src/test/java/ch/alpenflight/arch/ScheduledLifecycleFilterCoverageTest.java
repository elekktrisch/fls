package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.AlpenFlightApplication;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * S-137 design-time guardrail. Every {@code @Scheduled} method MUST carry
 * a {@link LifecycleStateFilter} with a non-empty state set:
 *
 * <ul>
 *   <li>missing annotation → build break (cross-Deployment leakage risk
 *       — a new job would silently iterate every state including
 *       {@code SANDBOX} stranger data + {@code DELETING} cascade-in-flight);</li>
 *   <li>empty {@code value()} → build break (the aspect's fail-closed
 *       behavior would silently skip the job forever, masking the
 *       missing decision).</li>
 * </ul>
 *
 * <p>Cross-cutting ops jobs that genuinely span every state declare each
 * state explicitly — the verbosity is the point.
 *
 * <p>No production {@code @Scheduled} method exists at S-137 ship-time;
 * S-081 lands the {@code @EnableScheduling} infrastructure and S-083+ the
 * per-job classes. This test passes vacuously today and lights up the
 * moment a job lands without the annotation.
 */
class ScheduledLifecycleFilterCoverageTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importPackagesOf(AlpenFlightApplication.class);

    @Test
    void every_scheduled_method_carries_a_non_empty_lifecycle_filter() {
        List<String> violations = new ArrayList<>();
        classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(Scheduled.class))
                .forEach(m -> checkFilter(m, violations));

        assertThat(violations)
                .as("Every @Scheduled method must carry @LifecycleStateFilter with at "
                        + "least one LifecycleState. Missing or empty: %s", violations)
                .isEmpty();
    }

    private static void checkFilter(JavaMethod method, List<String> violations) {
        if (!method.isAnnotatedWith(LifecycleStateFilter.class)) {
            violations.add(method.getFullName() + " (missing @LifecycleStateFilter)");
            return;
        }
        LifecycleStateFilter filter = method.getAnnotationOfType(LifecycleStateFilter.class);
        if (filter.value().length == 0) {
            violations.add(method.getFullName() + " (empty @LifecycleStateFilter)");
        }
    }
}

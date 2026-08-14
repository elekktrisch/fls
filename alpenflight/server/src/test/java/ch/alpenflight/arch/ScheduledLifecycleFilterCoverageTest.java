package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.AlpenFlightApplication;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.platform.scheduling.UnscopedScheduledJob;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

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
                .as("Every @Scheduled method must carry @LifecycleStateFilter (with at "
                        + "least one LifecycleState) OR @UnscopedScheduledJob. Missing or "
                        + "empty: %s", violations)
                .isEmpty();
    }

    private static void checkFilter(JavaMethod method, List<String> violations) {
        boolean unscoped = method.isAnnotatedWith(UnscopedScheduledJob.class);
        boolean lifecycleFilter = method.isAnnotatedWith(LifecycleStateFilter.class);
        if (!unscoped && !lifecycleFilter) {
            violations.add(method.getFullName()
                    + " (missing @LifecycleStateFilter or @UnscopedScheduledJob)");
            return;
        }
        if (unscoped && lifecycleFilter) {
            violations.add(method.getFullName()
                    + " (both @LifecycleStateFilter and @UnscopedScheduledJob — pick one)");
            return;
        }
        if (lifecycleFilter) {
            LifecycleStateFilter filter = method.getAnnotationOfType(LifecycleStateFilter.class);
            if (filter.value().length == 0) {
                violations.add(method.getFullName() + " (empty @LifecycleStateFilter)");
            }
        }
    }
}

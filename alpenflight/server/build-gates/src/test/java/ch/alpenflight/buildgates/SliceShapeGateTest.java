package ch.alpenflight.buildgates;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.buildgates.support.Slices;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class SliceShapeGateTest {

    private static final Set<String> DEEP_SLICE_PACKAGES = Set.of("domain", "application", "web", "infra");

    @Test
    void productionSlicesAreEitherThinOrFullyDeep() {
        JavaClasses classes = new ClassFileImporter().importPackages(Slices.PRODUCTION_MODULE_ROOTS);

        List<String> violations = violationsOf(classes, Slices.PRODUCTION_MODULE_ROOTS);

        assertThat(violations).as("Gate 1 (slice shape)").isEmpty();
    }

    @Test
    void fixturePartialDeepSliceIsCaught() {
        JavaClasses classes =
                new ClassFileImporter().importPackages("ch.alpenflight.buildgates.sliceshapegate.fixtures");

        List<String> violations =
                violationsOf(classes, "ch.alpenflight.buildgates.sliceshapegate.fixtures");

        assertThat(violations)
                .as("Gate 1 (slice shape) must catch a slice that carries only some of domain/application/web/infra")
                .isNotEmpty();
        assertThat(violations.get(0)).contains("Gate 1");
    }

    private static List<String> violationsOf(JavaClasses classes, String... moduleRootPackages) {
        List<String> violations = new ArrayList<>();
        for (var entry : Slices.childPackagesAcrossModuleRoots(classes, moduleRootPackages)) {
            String slice = entry.getKey();
            SortedSet<String> present = new TreeSet<>(entry.getValue());
            present.retainAll(DEEP_SLICE_PACKAGES);
            if (!present.isEmpty() && !present.equals(DEEP_SLICE_PACKAGES)) {
                violations.add(
                        "Gate 1 (slice shape): slice " + slice + " carries " + present
                                + " of " + DEEP_SLICE_PACKAGES + ", a deep slice must carry all four");
            }
        }
        return violations;
    }
}

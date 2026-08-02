package ch.alpenflight.platform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Guards the S-018 decision that ShedLock ships on the classpath but stays OFF.
 *
 * <p>AlpenFlight runs single-instance, so a distributed scheduler lock buys
 * nothing and adds a failure mode (a stale lock silently stops every job). The
 * library and its table are here so the eventual multi-instance cutover is one
 * annotation — this test is what makes adding that annotation a deliberate act
 * rather than a drive-by.
 */
class ShedLockNotActivatedTest {

    private static final String ENABLE_SCHEDULER_LOCK =
            "net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock";

    @Test
    void shedLockIsOnTheClasspath() throws ClassNotFoundException {
        assertThat(Class.forName(ENABLE_SCHEDULER_LOCK))
                .as("the cutover should not need a dependency change")
                .isNotNull();
    }

    @Test
    void noClassEnablesSchedulerLock() {
        JavaClasses ours = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ch.alpenflight");

        assertThat(ours.stream()
                .filter(c -> c.getAnnotations().stream()
                        .anyMatch(a -> ENABLE_SCHEDULER_LOCK.equals(a.getRawType().getName())))
                .map(c -> c.getName())
                .toList())
                .as("ShedLock is deliberately inert — activating it is a "
                        + "multi-instance decision, not a refactor")
                .isEmpty();
    }
}

package ch.alpenflight.arch;

import ch.alpenflight.AlpenFlightApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the Spring Modulith module structure: every bounded-context
 * top-level package under {@code ch.alpenflight} is its own module, and no
 * module reaches into another module's internals except through that
 * module's published API (named-interface package).
 *
 * <p>Inner-layer direction (no {@code web} → {@code infra}, no {@code domain}
 * → Spring web, etc.) is enforced by {@link LayeringRulesTest} — Modulith
 * only sees the inter-module surface.
 */
class ApplicationModulesTest {

    @Test
    void verifyModuleStructure() {
        ApplicationModules.of(AlpenFlightApplication.class).verify();
    }
}

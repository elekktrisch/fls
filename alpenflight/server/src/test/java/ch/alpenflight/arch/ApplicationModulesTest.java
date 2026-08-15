package ch.alpenflight.arch;

import ch.alpenflight.AlpenFlightApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModulesTest {

    @Test
    void verifyModuleStructure() {
        ApplicationModules.of(AlpenFlightApplication.class).verify();
    }
}

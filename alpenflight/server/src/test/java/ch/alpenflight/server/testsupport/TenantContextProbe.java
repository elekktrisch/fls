package ch.alpenflight.server.testsupport;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration
public class TenantContextProbe {

    public Optional<UUID> current() {
        return TenantTestContext.current();
    }
}

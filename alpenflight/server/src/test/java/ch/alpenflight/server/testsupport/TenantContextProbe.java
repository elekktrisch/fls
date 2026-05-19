package ch.alpenflight.server.testsupport;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Spring-injectable read-side for {@link TenantTestContext}. Tests that want
 * to assert the test extension actually pushed a value pull this bean and
 * call {@link #current()} from inside a {@code @Test} method.
 *
 * <p>Imported by {@code @Import(TenantContextProbe.class)} on tests that need
 * to read the context. {@link TestConfiguration} is meta-annotated with
 * {@code @Configuration}, so the class itself becomes the autowirable bean.
 */
@TestConfiguration
public class TenantContextProbe {

    public Optional<UUID> current() {
        return TenantTestContext.current();
    }
}

package ch.alpenflight.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code junit-platform.properties} invariants S-015 commits to.
 * {@link TenantTestContext} uses a {@code ThreadLocal} carrier; if parallel
 * test execution flips on, sibling test methods would alias each other's
 * tenant context and silently cross-contaminate. Re-enabling parallelism
 * is allowed only after the per-test data-isolation policy (ADR 0021) is
 * audited across the whole suite; until then this test fails the build.
 */
class JunitPlatformConfigTest {

    @Test
    void parallel_execution_pinned_off() throws IOException {
        Properties props = new Properties();
        try (InputStream in = JunitPlatformConfigTest.class
                .getResourceAsStream("/junit-platform.properties")) {
            assertThat(in).as("junit-platform.properties must exist on the test classpath").isNotNull();
            props.load(in);
        }
        assertThat(props.getProperty("junit.jupiter.execution.parallel.enabled"))
                .as("ThreadLocal tenant context requires parallel.enabled=false until ADR 0021 is audited")
                .isEqualTo("false");
    }
}

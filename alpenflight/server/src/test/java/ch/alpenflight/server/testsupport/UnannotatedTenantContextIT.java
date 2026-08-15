package ch.alpenflight.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(TenantContextProbe.class)
class UnannotatedTenantContextIT extends PostgresIntegrationTest {

    @Autowired
    private TenantContextProbe probe;

    @Test
    void no_with_tenant_yields_empty_context() {
        assertThat(probe.current()).isEmpty();
    }
}

package ch.alpenflight.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Meta-tests for the {@link PostgresIntegrationTest} base class + the
 * {@link WithTenant} / {@link TenantContextExtension} / {@link TenantTestContext}
 * primitives. Each {@code @Test} method asserts one invariant of the
 * test-infrastructure surface that downstream stories (S-022 and beyond)
 * consume; if any of these break, the consumers see a confusing failure
 * mode that this class catches first.
 */
@Import(TenantContextProbe.class)
@WithTenant(PostgresIntegrationTestSmokeIT.CLASS_LEVEL_TENANT)
class PostgresIntegrationTestSmokeIT extends PostgresIntegrationTest {

    static final String CLASS_LEVEL_TENANT = "019e30c3-2c00-7001-8000-0000000000aa";
    private static final String METHOD_LEVEL_TENANT = "019e30c3-2c00-7001-8000-0000000000bb";
    private static final String SWITCH_TARGET = "019e30c3-2c00-7001-8000-0000000000cc";

    @Autowired
    private TenantContextProbe probe;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void base_class_boots_with_flyway_migrated() {
        Integer migrations = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(migrations).as("at least the V1+V2 baseline must be applied").isGreaterThanOrEqualTo(2);
    }

    @Test
    void class_level_with_tenant_resolves_when_no_method_annotation() {
        assertThat(probe.current()).contains(UUID.fromString(CLASS_LEVEL_TENANT));
    }

    @Test
    @WithTenant(METHOD_LEVEL_TENANT)
    void method_level_with_tenant_overrides_class_level() {
        assertThat(probe.current()).contains(UUID.fromString(METHOD_LEVEL_TENANT));
    }

    @Test
    @WithTenant(CLASS_LEVEL_TENANT)
    void tenant_switch_via_runAs_restores_prior_after_block() {
        UUID outer = UUID.fromString(CLASS_LEVEL_TENANT);
        UUID inner = UUID.fromString(SWITCH_TARGET);
        TenantTestContext.runAs(inner, () ->
                assertThat(probe.current()).contains(inner));
        assertThat(probe.current()).contains(outer);
    }

    @Test
    @WithTenant(CLASS_LEVEL_TENANT)
    void runUnscoped_yields_no_tenant_sentinel_and_restores_prior_after_block() {
        UUID outer = UUID.fromString(CLASS_LEVEL_TENANT);
        TenantTestContext.runUnscoped(() ->
                assertThat(probe.current()).contains(TenantTestContext.NO_TENANT));
        assertThat(probe.current()).contains(outer);
    }
}

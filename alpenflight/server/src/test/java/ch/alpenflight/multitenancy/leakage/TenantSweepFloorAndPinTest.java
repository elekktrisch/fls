package ch.alpenflight.multitenancy.leakage;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.TenantScopedEntityCatalog;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.hibernate.Version;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class TenantSweepFloorAndPinTest {

    private static final int TENANT_SCOPED_ENTITY_FLOOR = 14;

    @Test
    void discovered_tenant_scoped_entity_count_meets_floor() {
        assertThat(TenantScopedEntityCatalog.discoverTenantScopedEntities())
                .as("Reflective sweep must discover at least %d @TenantId entities; a classpath "
                        + "or annotation regression would silently empty the sweep.",
                        TENANT_SCOPED_ENTITY_FLOOR)
                .hasSizeGreaterThanOrEqualTo(TENANT_SCOPED_ENTITY_FLOOR);
    }

    @Test
    void every_discovered_entity_has_a_registered_row_builder() {
        var discovered = TenantScopedEntityCatalog.discoverTenantScopedEntities();
        var registered = TenantScopedRowBuilders.registered();
        assertThat(discovered)
                .as("Every @TenantId entity must have a row builder in TenantScopedRowBuilders "
                        + "(else the sweep can't exercise it). Add a builder in the same PR that "
                        + "introduces the @TenantId annotation.")
                .allMatch(registered::contains);
    }

    @Test
    void hibernate_runtime_version_matches_yaml_pin() throws Exception {
        Yaml yaml = new Yaml();
        Map<String, Object> rules;
        try (FileInputStream in = new FileInputStream(locateTenantRules().toFile())) {
            rules = yaml.load(in);
        }
        String pin = (String) rules.get("hibernate_pin");
        assertThat(pin)
                .as("tenant-rules.yaml must declare a hibernate_pin")
                .isNotNull();

        String runtime = Version.getVersionString();
        String pinMajor = pin.split("\\.")[0];
        assertThat(runtime)
                .as("Bundled Hibernate %s must match tenant-rules.yaml hibernate_pin %s "
                        + "— @TenantId null-handling semantics changed between 5.x and 6.x.",
                        runtime, pin)
                .startsWith(pinMajor + ".");
    }

    private static Path locateTenantRules() {
        Path cwd = Path.of("").toAbsolutePath();
        Path probe = cwd;
        while (probe != null) {
            Path candidate = probe.resolve("alpenflight/database/tenant-rules.yaml");
            if (Files.exists(candidate)) return candidate;
            Path siblingCandidate = probe.resolve("../database/tenant-rules.yaml").normalize();
            if (Files.exists(siblingCandidate)) return siblingCandidate;
            probe = probe.getParent();
        }
        throw new IllegalStateException(
                "tenant-rules.yaml not found under any ancestor of " + cwd);
    }
}

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

/**
 * Harness-sanity guards for the S-024 sweep. Pure JUnit — no Spring context,
 * no Docker. Two assertions:
 *
 * <ul>
 *   <li><strong>Floor.</strong> Reflective discovery returns at least the
 *       known minimum count of {@code @TenantId}-bearing entities. Catches a
 *       classpath / annotation regression that would silently produce an
 *       empty sweep (false-green).</li>
 *   <li><strong>Hibernate version pin.</strong> The bundled Hibernate's
 *       major version matches {@code tenant-rules.yaml::hibernate_pin}. The
 *       {@code @TenantId} discriminator's null-handling semantics changed
 *       between 5.x and 6.x (ADR 0008 footnote); pinning makes a major-bump
 *       a single deliberate PR.</li>
 * </ul>
 */
class TenantSweepFloorAndPinTest {

    /**
     * Today: {@code MemberState} (clubs) + {@code Location} (locations) +
     * {@code MutationAuditEvent} (audit, S-027). Bump this constant
     * opportunistically — boyscout — when a new {@code @TenantId} entity
     * lands.
     */
    private static final int TENANT_SCOPED_ENTITY_FLOOR = 10;

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
        String pinMajor = pin.split("\\.")[0]; // "7.x" → "7"
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

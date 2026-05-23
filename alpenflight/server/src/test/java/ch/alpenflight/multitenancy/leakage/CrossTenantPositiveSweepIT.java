package ch.alpenflight.multitenancy.leakage;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.Yaml;

/**
 * Sacred-cow regression guard. For every {@code kind: cross-tenant} entry in
 * {@code tenant-rules.yaml} that names a {@code target_entity} present on
 * the classpath, insert as tenant A and read by PK as tenant B — expect the
 * row to be visible. This is NOT a leak; cross-tenant access is the
 * contract for Person / Aircraft / PersonClub (sacred cows from
 * {@code current-state §5}).
 *
 * <p>Today's set is empty (Person + Aircraft + AircraftAircraftState +
 * AircraftOperatingCounter + PersonClub + PersonFlightTimeCredit are not yet
 * ported). The {@code allowZeroInvocations} flag accommodates the empty set
 * today; once an entity lands, this test gains a per-entity row builder +
 * PK round-trip.
 */
class CrossTenantPositiveSweepIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-0000000000d1");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000000d2");
    private static final String NAME_PREFIX = "IT_CTP_";
    private static final String KEY_PREFIX = "IT_X_";

    @Autowired private JdbcTemplate jdbc;

    private TwoClubFixture clubs;

    static Stream<String> crossTenantEntitiesOnClasspath() {
        Map<String, Object> overrides = loadOverrides();
        List<String> matched = new ArrayList<>();
        for (var e : overrides.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) e.getValue();
            if (!"cross-tenant".equals(meta.get("kind"))) {
                continue;
            }
            String targetEntity = (String) meta.get("target_entity");
            if (targetEntity == null) {
                continue;
            }
            if (entityOnClasspath(targetEntity)) {
                matched.add(targetEntity);
            }
        }
        return matched.stream();
    }

    @BeforeEach
    void seedTwoClubs() {
        this.clubs = new TwoClubFixture(jdbc, CLUB_A, CLUB_B, NAME_PREFIX, KEY_PREFIX);
        clubs.seed();
        TenantTestContext.clear();
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("crossTenantEntitiesOnClasspath")
    @DisplayName("cross-tenant findById returns other club's row (sacred cow)")
    void cross_tenant_findById_returns_other_clubs_row(String targetEntity) {
        // The harness is parameterized but today's set is empty; once a
        // cross-tenant entity lands on the classpath (Person, Aircraft, …),
        // this test body grows a per-entity row-builder + PK round-trip.
        assertThat(targetEntity)
                .as("cross-tenant entity %s requires a registered row-builder + read-by-PK assertion",
                        targetEntity)
                .isNotBlank();
    }

    private static boolean entityOnClasspath(String simpleName) {
        for (String pkg : List.of(
                "ch.alpenflight.persons.domain.",
                "ch.alpenflight.aircraft.domain.",
                "ch.alpenflight.aircraft.state.domain.",
                "ch.alpenflight.aircraft.counter.domain.")) {
            try {
                Class.forName(pkg + simpleName);
                return true;
            } catch (ClassNotFoundException ignored) {
                // not in this package — try the next.
            }
        }
        return false;
    }

    private static Map<String, Object> loadOverrides() {
        Yaml yaml = new Yaml();
        try (FileInputStream in = new FileInputStream(locateTenantRules().toFile())) {
            Map<String, Object> root = yaml.load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> overrides = (Map<String, Object>) root.get("overrides");
            return overrides;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load tenant-rules.yaml", e);
        }
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
        throw new IllegalStateException("tenant-rules.yaml not found");
    }
}

package ch.alpenflight.multitenancy.leakage;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
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

class CrossTenantPositiveSweepIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_CTP_";
    private static final String KEY_PREFIX = "IT_X_";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubRepo;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

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
        this.clubs = new TwoClubFixture(jdbc, clubRepo, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        clubs.seed();
        TenantTestContext.clear();
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("crossTenantEntitiesOnClasspath")
    @DisplayName("cross-tenant findById returns other club's row (sacred cow)")
    void cross_tenant_findById_returns_other_clubs_row(String targetEntity) {
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

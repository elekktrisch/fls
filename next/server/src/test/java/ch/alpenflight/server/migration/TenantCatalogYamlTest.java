package ch.alpenflight.server.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pure-YAML assertions over {@code next/database/tenant-rules.yaml}. No
 * Spring, no Docker, no DataSource — these check the tenant catalog's
 * declarations (type pins, classification, PII column lists) against the
 * ADRs that govern them.
 *
 * <p>Split out of {@code TenantCatalogConsistencyTest} so a contributor
 * without Docker can still run the catalog static checks via
 * {@code ./gradlew check}, and so CI doesn't boot a Postgres container
 * just to read a YAML file.
 */
class TenantCatalogYamlTest {

    private static Map<String, Object> tenantRules;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadTenantRules() throws Exception {
        Path yamlPath = locateTenantRules();
        try (FileInputStream in = new FileInputStream(yamlPath.toFile())) {
            tenantRules = new Yaml().load(in);
        }
    }

    @Test
    void tenant_rules_yaml_tenant_id_type_is_uuid() {
        assertThat(tenantRules.get("tenant_id_type"))
                .as("ADR 0019 pinned UUID; tenant column type follows")
                .isEqualTo("UUID");
    }

    @Test
    void tenant_rules_yaml_hibernate_pin_is_7x() {
        assertThat(tenantRules.get("hibernate_pin"))
                .as("ADR 0001 + 0019 — Hibernate 7.x required for UUID v7 generation")
                .isEqualTo("7.x");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenant_rules_yaml_pii_columns_present_for_person_and_user() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> person = (Map<String, Object>) overrides.get("Persons");
        Map<String, Object> user = (Map<String, Object>) overrides.get("Users");
        Map<String, Object> personClub = (Map<String, Object>) overrides.get("PersonClub");

        assertThat(person)
                .as("Persons override must enumerate pii_columns")
                .containsKey("pii_columns");
        assertThat((List<String>) person.get("pii_columns"))
                .as("PII column list on Persons must include at least the direct identifiers")
                .contains("firstname", "lastname", "birthday", "email_private", "licence_number");

        assertThat(user)
                .as("Users override must enumerate pii_columns")
                .containsKey("pii_columns");
        assertThat((List<String>) user.get("pii_columns"))
                .contains("username", "phone_number");

        assertThat(personClub).containsKey("pii_columns");
        assertThat((List<String>) personClub.get("pii_columns"))
                .as("member_number is quasi-PII (name+number reveals membership)")
                .contains("member_number");
    }

    @Test
    @SuppressWarnings("unchecked")
    void member_state_reclassified_to_tenant_scoped() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> memberStates = (Map<String, Object>) overrides.get("MemberStates");
        assertThat(memberStates.get("kind"))
                .as("ADR 0018 + legacy ClubId NOT NULL → MemberStates is tenant-scoped, internal to Club")
                .isEqualTo("tenant-scoped");
    }

    @Test
    @SuppressWarnings("unchecked")
    void person_category_reclassified_to_tenant_scoped() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> personCategories = (Map<String, Object>) overrides.get("PersonCategories");
        assertThat(personCategories.get("kind")).isEqualTo("tenant-scoped");
    }

    /**
     * tenant-rules.yaml lives in a sibling Gradle module (next/database/).
     * Resolution strategy: walk up from the working dir until we find a
     * directory containing the yaml, then drop the path off that base. Works
     * both when running from the repo root and from {@code next/server/}.
     */
    private static Path locateTenantRules() {
        Path cwd = Path.of("").toAbsolutePath();
        Path probe = cwd;
        while (probe != null) {
            Path candidate = probe.resolve("next/database/tenant-rules.yaml");
            if (Files.exists(candidate)) return candidate;
            Path siblingCandidate = probe.resolve("../database/tenant-rules.yaml").normalize();
            if (Files.exists(siblingCandidate)) return siblingCandidate;
            probe = probe.getParent();
        }
        throw new IllegalStateException(
                "tenant-rules.yaml not found under any ancestor of " + cwd
                        + " — expected at <repo>/next/database/tenant-rules.yaml");
    }
}

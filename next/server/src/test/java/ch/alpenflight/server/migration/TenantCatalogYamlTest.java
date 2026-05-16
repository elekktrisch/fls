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

    @Test
    @SuppressWarnings("unchecked")
    void flight_type_reclassified_to_tenant_scoped() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> flightTypes = (Map<String, Object>) overrides.get("FlightTypes");
        assertThat(flightTypes.get("kind"))
                .as("S-013 reclassifies FlightTypes from reference to tenant-scoped (legacy FlightType.cs:25 has ClubId NOT NULL)")
                .isEqualTo("tenant-scoped");
        assertThat(flightTypes.get("tenant_column")).isEqualTo("operating_club_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aircraft_tenant_column_renamed_to_owner_club_id_with_legacy_pin() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> aircrafts = (Map<String, Object>) overrides.get("Aircrafts");
        assertThat(aircrafts.get("kind"))
                .as("Aircrafts reclassified to cross-tenant 2026-05-16")
                .isEqualTo("cross-tenant");
        assertThat(aircrafts.get("owner_column"))
                .as("aircraft tenant column renamed: legacy OwnerClubId → new owner_club_id")
                .isEqualTo("owner_club_id");
        assertThat(aircrafts.get("tenant_column_legacy"))
                .as("legacy OwnerClubId pin must remain for S-016 cutover")
                .isEqualTo("OwnerClubId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenant_rules_yaml_pii_columns_present_for_flight_aircraft_location() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> flights = (Map<String, Object>) overrides.get("Flights");
        Map<String, Object> aircrafts = (Map<String, Object>) overrides.get("Aircrafts");
        Map<String, Object> locations = (Map<String, Object>) overrides.get("Locations");
        Map<String, Object> aircraftStates = (Map<String, Object>) overrides.get("AircraftAircraftStates");

        assertThat((List<String>) flights.get("pii_columns"))
                .as("Flights pii_columns must enumerate free-text PII")
                .contains("comment", "incident_comment", "validation_errors",
                          "outbound_route", "inbound_route");

        assertThat((List<String>) aircrafts.get("pii_columns"))
                .as("Aircrafts pii_columns must list at least the free-text comment")
                .contains("comment");

        assertThat((List<String>) aircrafts.get("sensitive_columns"))
                .as("Aircrafts sensitive_columns must include flarm_id + spot_link")
                .contains("flarm_id", "spot_link");

        assertThat((List<String>) locations.get("pii_columns"))
                .as("Locations pii_columns must include description")
                .contains("description");

        assertThat((List<String>) aircraftStates.get("pii_columns"))
                .as("AircraftAircraftStates pii_columns must include remarks")
                .contains("remarks");
    }

    @Test
    @SuppressWarnings("unchecked")
    void article_classified_as_tenant_scoped() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> articles = (Map<String, Object>) overrides.get("Articles");
        assertThat(articles)
                .as("Articles override must exist (added in S-013)")
                .isNotNull();
        assertThat(articles.get("kind")).isEqualTo("tenant-scoped");
        assertThat(articles.get("tenant_column")).isEqualTo("operating_club_id");
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

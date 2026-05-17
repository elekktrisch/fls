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
    void flight_tenant_scope_precondition_met() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> flights = (Map<String, Object>) overrides.get("Flights");
        List<String> preconditions = (List<String>) flights.get("preconditions");
        assertThat(preconditions)
                .as("Flights.preconditions must enumerate the per-flight operating_club_id contract")
                .isNotNull()
                .anyMatch(p -> p.toLowerCase(java.util.Locale.ROOT).contains("operating_club_id")
                        && p.toLowerCase(java.util.Locale.ROOT).contains("per-flight"))
                .anyMatch(p -> p.toLowerCase(java.util.Locale.ROOT).contains("s-022")
                        && p.toLowerCase(java.util.Locale.ROOT).contains("charter"));
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

    /** S-014 reclassification: AircraftReservationTypes (legacy ClubId NOT NULL → per-club). */
    @Test
    @SuppressWarnings("unchecked")
    void s014_aircraft_reservation_types_reclassified_to_tenant_scoped() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> arvTypes = (Map<String, Object>) overrides.get("AircraftReservationTypes");
        assertThat(arvTypes.get("kind"))
                .as("S-014 reclassifies AircraftReservationTypes from reference to tenant-scoped")
                .isEqualTo("tenant-scoped");
        assertThat(arvTypes.get("tenant_column")).isEqualTo("operating_club_id");
    }

    /** S-014 reclassification: PlanningDayAssignmentTypes (legacy ClubId NOT NULL → per-club). */
    @Test
    @SuppressWarnings("unchecked")
    void s014_planning_day_assignment_types_reclassified_to_tenant_scoped() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> pdaTypes = (Map<String, Object>) overrides.get("PlanningDayAssignmentTypes");
        assertThat(pdaTypes.get("kind"))
                .as("S-014 reclassifies PlanningDayAssignmentTypes from reference to tenant-scoped")
                .isEqualTo("tenant-scoped");
        assertThat(pdaTypes.get("tenant_column")).isEqualTo("operating_club_id");
    }

    /**
     * 2026-05-16 Aircraft-cross-tenant amendment hand-off to S-014:
     * AircraftReservations.ride_through_targets must list both Persons and
     * Aircrafts. S-024 leakage CI reads this to parameterize the cross-tenant
     * FK roster.
     */
    @Test
    @SuppressWarnings("unchecked")
    void s014_aircraft_reservations_ride_through_includes_aircrafts() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> arv = (Map<String, Object>) overrides.get("AircraftReservations");
        List<String> rideThrough = (List<String>) arv.get("ride_through_targets");
        assertThat(rideThrough)
                .as("AircraftReservations.ride_through_targets must include both Persons and Aircrafts (2026-05-16 amendment)")
                .containsExactlyInAnyOrder("Persons", "Aircrafts");
    }

    /** S-014 PII catalog extension: 9 frozen recipient snapshot cols + 2 free-text quasi-PII. */
    @Test
    @SuppressWarnings("unchecked")
    void s014_deliveries_pii_columns_include_9_recipient_snapshot_columns() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> deliveries = (Map<String, Object>) overrides.get("Deliveries");
        List<String> piiCols = (List<String>) deliveries.get("pii_columns");
        assertThat(piiCols)
                .as("Deliveries.pii_columns must enumerate the 9 frozen recipient snapshot columns")
                .contains("recipient_name", "recipient_firstname", "recipient_lastname",
                        "recipient_address_line1", "recipient_address_line2",
                        "recipient_zip_code", "recipient_city", "recipient_country_name",
                        "recipient_person_club_member_number");
    }

    /** S-014 DSAR exemption: Booked deliveries are FADP-DSAR-exempt per Swiss OR Art. 957a. */
    @Test
    @SuppressWarnings("unchecked")
    void s014_deliveries_fadp_dsar_exempt_when_booked() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> deliveries = (Map<String, Object>) overrides.get("Deliveries");
        assertThat(deliveries.get("fadp_dsar_retention_exempt_when"))
                .as("Deliveries must carry the DSAR retention exemption clause (OR Art. 957a)")
                .isNotNull()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("process_state_id");
    }

    /** S-014 AccountingRuleFilters pii_blob (filter_config jsonb may carry member_number lists). */
    @Test
    @SuppressWarnings("unchecked")
    void s014_accounting_rule_filters_marked_pii_blob() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        Map<String, Object> arf = (Map<String, Object>) overrides.get("AccountingRuleFilters");
        assertThat(arf.get("pii_blob"))
                .as("AccountingRuleFilters.filter_config jsonb may carry matched_club_member_numbers — pii_blob: true")
                .isEqualTo(true);
    }

    /** S-014 new tenant-scoped entries: DeliveryCreationTest + DeliveryCreationTestItem + ClubDeliveryNumberCounter. */
    @Test
    @SuppressWarnings("unchecked")
    void s014_new_tenant_scoped_entries_present() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        for (String entry : List.of("DeliveryCreationTest", "DeliveryCreationTestItem", "ClubDeliveryNumberCounter")) {
            Map<String, Object> e = (Map<String, Object>) overrides.get(entry);
            assertThat(e).as("%s tenant-rules override must exist", entry).isNotNull();
            assertThat(e.get("kind")).isEqualTo("tenant-scoped");
        }
    }

    /** S-014 SYSTEM_GLOBAL reference tables stay reference (no ClubId in legacy). */
    @Test
    @SuppressWarnings("unchecked")
    void s014_system_global_reference_tables_stay_reference() {
        Map<String, Object> overrides = (Map<String, Object>) tenantRules.get("overrides");
        for (String entry : List.of("AccountingRuleFilterTypes", "AccountingUnitTypes")) {
            Map<String, Object> e = (Map<String, Object>) overrides.get(entry);
            assertThat(e.get("kind"))
                    .as("%s must stay kind: reference (no legacy ClubId)", entry)
                    .isEqualTo("reference");
        }
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

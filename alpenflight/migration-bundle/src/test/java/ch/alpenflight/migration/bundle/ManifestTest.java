package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManifestTest {

    private static final EntityPolicy FULL_PORT = new EntityPolicy(
            EntityPolicy.PortPolicy.FULL_PORT,
            EntityPolicy.TombstonePolicy.PORT_ALL,
            Set.of(),
            List.of("id"));

    @Test
    void rejectsCoverageGapAcrossEntityTypeValues() {
        Map<EntityType, EntityPolicy> policies = new EnumMap<>(EntityType.class);
        policies.put(EntityType.COUNTRY, FULL_PORT);
        assertThatThrownBy(() -> new Manifest(1, policies, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Manifest does not cover EntityType values");
    }

    @Test
    void rejectsOverlapBetweenPoliciesAndUnmappedReason() {
        Map<EntityType, EntityPolicy> policies = allFullPort();
        Map<EntityType, String> unmapped = Map.of(EntityType.COUNTRY, "duplicate");
        assertThatThrownBy(() -> new Manifest(1, policies, unmapped))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both entityPolicies and unmappedReason");
    }

    @Test
    void rejectsNonPositiveSchemaVersion() {
        Map<EntityType, EntityPolicy> policies = allFullPort();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Manifest(0, policies, Map.of()))
                .withMessageContaining("schemaVersion");
    }

    @Test
    void roundTripsThroughJackson() throws Exception {
        Manifest original = new Manifest(
                Manifest.CURRENT_SCHEMA_VERSION, allFullPort(), Map.of());
        ObjectMapper json = new ObjectMapper();
        String serialized = json.writeValueAsString(original);
        Manifest decoded = json.readValue(serialized, Manifest.class);
        assertThat(decoded.schemaVersion())
                .isEqualTo(Manifest.CURRENT_SCHEMA_VERSION);
        assertThat(decoded.entityPolicies()).hasSize(EntityType.values().length);
        assertThat(decoded.entityPolicies().keySet())
                .as("entityPolicies iteration must follow EntityType declaration order "
                        + "(EnumMap-backed) — consumers walk this in ingest order")
                .containsExactly(EntityType.values());
    }

    @Test
    void acceptsTenantBypassFksOnTheAllowListedIdentityGroupEntities() {
        EntityPolicy withBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("person_id"),
                List.of("id"));
        Map<EntityType, EntityPolicy> policies = allFullPort();
        policies.put(EntityType.USER, withBypass);
        policies.put(EntityType.PERSON_CLUB, withBypass);
        policies.put(EntityType.PERSON_CATEGORY_ASSIGNMENT, withBypass);
        assertThat(new Manifest(1, policies, Map.of())).isNotNull();
    }

    @Test
    void acceptsTenantBypassFksOnTheAllowListedFlightGroupEntities() {
        EntityPolicy aircraftBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("aircraft_owner_person_id", "homebase_id"),
                List.of("id"));
        EntityPolicy aircraftStateBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("noticed_by_person_id"),
                List.of("id"));
        EntityPolicy flightBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("aircraft_id"),
                List.of("id"));
        EntityPolicy flightCrewBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("person_id"),
                List.of("id"));
        Map<EntityType, EntityPolicy> policies = allFullPort();
        policies.put(EntityType.AIRCRAFT, aircraftBypass);
        policies.put(EntityType.AIRCRAFT_AIRCRAFT_STATE, aircraftStateBypass);
        policies.put(EntityType.FLIGHT, flightBypass);
        policies.put(EntityType.FLIGHT_CREW, flightCrewBypass);
        assertThat(new Manifest(1, policies, Map.of())).isNotNull();
    }

    @Test
    void rejectsTenantBypassFksOnLocationEvenInFlightGroup() {
        EntityPolicy withBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("club_id"),
                List.of("id"));
        Map<EntityType, EntityPolicy> policies = allFullPort();
        policies.put(EntityType.LOCATION, withBypass);
        assertThatThrownBy(() -> new Manifest(1, policies, Map.of()))
                .as("Location is tenant-scoped per V7; club_id IS the @TenantId "
                        + "discriminator, not a bypass FK")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOCATION");
    }

    @Test
    void rejectsTenantBypassFksOnFlightTypeAndStartType() {
        EntityPolicy withBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("operating_club_id"),
                List.of("id"));
        for (EntityType referenceEntity : List.of(
                EntityType.FLIGHT_TYPE, EntityType.START_TYPE)) {
            Map<EntityType, EntityPolicy> policies = allFullPort();
            policies.put(referenceEntity, withBypass);
            assertThatThrownBy(() -> new Manifest(1, policies, Map.of()))
                    .as("%s has no cross-tenant FK; widening the allow-list "
                            + "with it would let a future producer smuggle a "
                            + "bypass declaration past the structural validator",
                            referenceEntity)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(referenceEntity.name());
        }
    }

    @Test
    void rejectsTenantBypassFksOnAircraftOperatingCounter() {
        EntityPolicy withBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("aircraft_id"),
                List.of("id"));
        Map<EntityType, EntityPolicy> policies = allFullPort();
        policies.put(EntityType.AIRCRAFT_OPERATING_COUNTER, withBypass);
        assertThatThrownBy(() -> new Manifest(1, policies, Map.of()))
                .as("AircraftOperatingCounter only references its parent Aircraft "
                        + "intra-aggregate — no cross-tenant FK earns a bypass slot")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AIRCRAFT_OPERATING_COUNTER");
    }

    @Test
    void rejectsTenantBypassFksOnReferenceEntities() {
        EntityPolicy withBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("legacy_guid"),
                List.of("id"));
        Map<EntityType, EntityPolicy> policies = allFullPort();
        policies.put(EntityType.LANGUAGE, withBypass);
        assertThatThrownBy(() -> new Manifest(1, policies, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LANGUAGE")
                .hasMessageContaining("tenantBypassFks");
    }

    @Test
    void rejectsTenantBypassFksOnClub() {
        EntityPolicy withBypass = new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                Set.of("country_id"),
                List.of("id"));
        Map<EntityType, EntityPolicy> policies = allFullPort();
        policies.put(EntityType.CLUB, withBypass);
        assertThatThrownBy(() -> new Manifest(1, policies, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLUB");
    }

    private static Map<EntityType, EntityPolicy> allFullPort() {
        Map<EntityType, EntityPolicy> policies = new HashMap<>();
        for (EntityType type : EntityType.values()) {
            policies.put(type, FULL_PORT);
        }
        return policies;
    }
}

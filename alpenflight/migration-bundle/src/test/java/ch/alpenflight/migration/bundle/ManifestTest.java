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
    }

    private static Map<EntityType, EntityPolicy> allFullPort() {
        Map<EntityType, EntityPolicy> policies = new HashMap<>();
        for (EntityType type : EntityType.values()) {
            policies.put(type, FULL_PORT);
        }
        return policies;
    }
}

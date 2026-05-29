package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bundle manifest. Jackson-serialized JSON on the wire; consumed by S-139
 * (producer) and S-141 (consumer). The constructor enforces the coverage
 * gate: every {@link EntityType} value must either appear in
 * {@code entityPolicies} or {@code unmappedReason}.
 *
 * @param schemaVersion     bumped when the wire format changes; mismatch
 *                          rejected by the consumer pre-COPY with distinct
 *                          UPGRADE / DOWNGRADE error codes.
 * @param entityPolicies    per-entity port policy.
 * @param unmappedReason    per-entity "WHY not mapped" string (e.g. for
 *                          legacy tables intentionally dropped at cutover).
 */
public record Manifest(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("entityPolicies") Map<EntityType, EntityPolicy> entityPolicies,
        @JsonProperty("unmappedReason") Map<EntityType, String> unmappedReason) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonCreator
    public Manifest {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException(
                    "schemaVersion must be positive, got " + schemaVersion);
        }
        entityPolicies = copyToEnumMap(entityPolicies);
        unmappedReason = copyToEnumMap(unmappedReason);
        validateCoverage(entityPolicies, unmappedReason);
        entityPolicies = Map.copyOf(entityPolicies);
        unmappedReason = Map.copyOf(unmappedReason);
    }

    private static <V> Map<EntityType, V> copyToEnumMap(Map<EntityType, V> source) {
        Map<EntityType, V> destination = new EnumMap<>(EntityType.class);
        if (source != null) {
            destination.putAll(source);
        }
        return destination;
    }

    /**
     * Every {@link EntityType} must be either policy-mapped or in the
     * unmapped-reason map. Overlap is rejected — an entity is one or the
     * other, never both.
     */
    private static void validateCoverage(
            Map<EntityType, EntityPolicy> policies,
            Map<EntityType, String> unmapped) {
        Set<EntityType> overlap = policies.keySet().stream()
                .filter(unmapped::containsKey)
                .collect(Collectors.toUnmodifiableSet());
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Entity in both entityPolicies and unmappedReason: " + overlap);
        }
        Set<EntityType> uncovered = java.util.EnumSet.allOf(EntityType.class);
        uncovered.removeAll(policies.keySet());
        uncovered.removeAll(unmapped.keySet());
        if (!uncovered.isEmpty()) {
            throw new IllegalArgumentException(
                    "Manifest does not cover EntityType values: " + uncovered);
        }
    }
}

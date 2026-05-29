package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
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
 * <p>Unknown wire fields fail-fast at parse via
 * {@link JsonIgnoreProperties}: a tampered or forward-incompatible bundle
 * is rejected before the COPY begins.
 *
 * <p>Distinct from {@link UnmappedTables#REGISTRY} which is keyed by the
 * legacy table name (no destination {@link EntityType} exists).
 * {@code unmappedReason} here is keyed by {@link EntityType} — entities
 * the bundle MAY omit even though they have a destination table.
 *
 * @param schemaVersion     bumped when the wire format changes; mismatch
 *                          rejected by the consumer pre-COPY with distinct
 *                          UPGRADE / DOWNGRADE error codes.
 * @param entityPolicies    per-entity port policy.
 * @param unmappedReason    per-{@link EntityType} "WHY not mapped" string
 *                          for entities the bundle MAY omit.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
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
        Map<EntityType, EntityPolicy> policies = copyToEnumMap(entityPolicies);
        Map<EntityType, String> reasons = copyToEnumMap(unmappedReason);
        validateCoverage(policies, reasons);
        entityPolicies = Collections.unmodifiableMap(policies);
        unmappedReason = Collections.unmodifiableMap(reasons);
    }

    private static <V> EnumMap<EntityType, V> copyToEnumMap(Map<EntityType, V> source) {
        EnumMap<EntityType, V> destination = new EnumMap<>(EntityType.class);
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

package ch.alpenflight.migration.bundle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = false)
public record Manifest(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("entityPolicies") Map<EntityType, EntityPolicy> entityPolicies,
        @JsonProperty("unmappedReason") Map<EntityType, String> unmappedReason) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Set<EntityType> TENANT_BYPASS_ALLOW_LIST = Set.of(
            EntityType.USER,
            EntityType.PERSON_CLUB,
            EntityType.PERSON_CATEGORY_ASSIGNMENT,
            EntityType.AIRCRAFT,
            EntityType.AIRCRAFT_AIRCRAFT_STATE,
            EntityType.FLIGHT,
            EntityType.FLIGHT_CREW,
            EntityType.AIRCRAFT_RESERVATION,
            EntityType.PLANNING_DAY_ASSIGNMENT,
            EntityType.DELIVERY,
            EntityType.PERSON_FLIGHT_TIME_CREDIT,
            EntityType.AUDIT_LOG);

    public static Set<EntityType> tenantBypassAllowList() {
        return TENANT_BYPASS_ALLOW_LIST;
    }

    @JsonCreator
    public Manifest {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException(
                    "schemaVersion must be positive, got " + schemaVersion);
        }
        Map<EntityType, EntityPolicy> policies = copyToEnumMap(entityPolicies);
        Map<EntityType, String> reasons = copyToEnumMap(unmappedReason);
        validateCoverage(policies, reasons);
        validateTenantBypassAllowList(policies);
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

    private static void validateTenantBypassAllowList(
            Map<EntityType, EntityPolicy> policies) {
        for (Map.Entry<EntityType, EntityPolicy> entry : policies.entrySet()) {
            EntityType entity = entry.getKey();
            Set<String> bypassFks = entry.getValue().tenantBypassFks();
            if (!bypassFks.isEmpty() && !TENANT_BYPASS_ALLOW_LIST.contains(entity)) {
                throw new IllegalArgumentException(
                        "Entity " + entity + " declares tenantBypassFks " + bypassFks
                                + " but is not on the cross-tenant allow-list "
                                + TENANT_BYPASS_ALLOW_LIST + ". Only these entities may "
                                + "legitimately cross tenants per ADR 0008.");
            }
        }
    }
}

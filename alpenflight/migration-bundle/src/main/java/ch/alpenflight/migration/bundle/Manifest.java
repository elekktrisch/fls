package ch.alpenflight.migration.bundle;

import ch.alpenflight.migration.bundle.TenantBypassGrant.CrossTenantReason;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = false)
public record Manifest(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("entityPolicies") Map<EntityType, EntityPolicy> entityPolicies,
        @JsonProperty("unmappedReason") Map<EntityType, String> unmappedReason) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Map<EntityType, TenantBypassGrant> REVIEWED_CROSS_TENANT_GRANTS =
            reviewedCrossTenantGrants();

    private static Map<EntityType, TenantBypassGrant> reviewedCrossTenantGrants() {
        EnumMap<EntityType, TenantBypassGrant> grants = new EnumMap<>(EntityType.class);
        grants.put(EntityType.USER, TenantBypassGrant.forOneColumn(
                "person_id", CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        grants.put(EntityType.PERSON_CLUB, TenantBypassGrant.forOneColumn(
                "person_id", CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        grants.put(EntityType.PERSON_CATEGORY_ASSIGNMENT, TenantBypassGrant.forOneColumn(
                "person_id", CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        grants.put(EntityType.AIRCRAFT, new TenantBypassGrant(Map.of(
                "aircraft_owner_person_id",
                CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO,
                "homebase_id",
                CrossTenantReason.HOMEBASE_LOCATION_OF_A_SHARED_AIRCRAFT)));
        grants.put(EntityType.AIRCRAFT_AIRCRAFT_STATE, TenantBypassGrant.forOneColumn(
                "noticed_by_person_id",
                CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        grants.put(EntityType.FLIGHT, TenantBypassGrant.forOneColumn(
                "aircraft_id", CrossTenantReason.AIRCRAFT_SHARED_BY_EVERY_CLUB_THAT_OPERATES_IT));
        grants.put(EntityType.FLIGHT_CREW, TenantBypassGrant.forOneColumn(
                "person_id", CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        grants.put(EntityType.AIRCRAFT_RESERVATION, new TenantBypassGrant(Map.of(
                "aircraft_id",
                CrossTenantReason.AIRCRAFT_SHARED_BY_EVERY_CLUB_THAT_OPERATES_IT,
                "pilot_person_id",
                CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO,
                "second_crew_person_id",
                CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO)));
        grants.put(EntityType.PLANNING_DAY_ASSIGNMENT, TenantBypassGrant.forOneColumn(
                "assigned_person_id",
                CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        grants.put(EntityType.DELIVERY, TenantBypassGrant.forOneColumn(
                "recipient_person_id",
                CrossTenantReason.RECIPIENT_PERSON_FROZEN_INTO_A_DELIVERED_ACCOUNTING_SNAPSHOT));
        grants.put(EntityType.PERSON_FLIGHT_TIME_CREDIT, TenantBypassGrant.forOneColumn(
                "person_id", CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        grants.put(EntityType.AUDIT_LOG, TenantBypassGrant.forOneColumn(
                "actor_user_id",
                CrossTenantReason
                        .HISTORICAL_ACTOR_USER_OF_AN_AUDITED_CHANGE_NEVER_ITS_OWNING_TENANT));
        return Collections.unmodifiableMap(grants);
    }

    public static Map<EntityType, TenantBypassGrant> reviewedCrossTenantGrantsByEntity() {
        return REVIEWED_CROSS_TENANT_GRANTS;
    }

    public static Set<EntityType> tenantBypassAllowList() {
        return REVIEWED_CROSS_TENANT_GRANTS.keySet();
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
            if (bypassFks.isEmpty()) {
                continue;
            }
            TenantBypassGrant grant = REVIEWED_CROSS_TENANT_GRANTS.get(entity);
            if (grant == null) {
                throw new IllegalArgumentException(
                        "Entity " + entity + " declares tenantBypassFks " + bypassFks
                                + " but is not on the cross-tenant allow-list "
                                + REVIEWED_CROSS_TENANT_GRANTS.keySet() + ". Only these entities "
                                + "may legitimately cross tenants per ADR 0008.");
            }
            Set<String> columnsOutsideTheGrant = new TreeSet<>(bypassFks);
            columnsOutsideTheGrant.removeAll(grant.grantedForeignKeyColumns());
            if (!columnsOutsideTheGrant.isEmpty()) {
                throw new IllegalArgumentException(
                        "Entity " + entity + " declares tenantBypassFks "
                                + columnsOutsideTheGrant + " that its reviewed cross-tenant "
                                + "grant does not cover "
                                + grant.crossTenantReasonByForeignKeyColumn()
                                + ". Widening a grant is a deliberate reviewed edit per "
                                + "ADR 0008.");
            }
        }
    }
}

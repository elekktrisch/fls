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

    /**
     * Entities allowed to declare a non-empty {@code tenantBypassFks} per
     * ADR 0008 + the S-184, S-185, and S-186 Security plans.
     *
     * <ul>
     *   <li>Identity group (S-184): {@code User.person_id},
     *       {@code PersonClub.person_id},
     *       {@code PersonCategoryAssignment.person_id} all resolve through
     *       the per-bundle cross-tenant Person sub-map.</li>
     *   <li>Flight group (S-185): cross-tenant Person + Aircraft refs.
     *       {@code Flight.aircraft_id} crosses to cross-tenant Aircraft;
     *       {@code FlightCrew.person_id} and
     *       {@code AircraftAircraftState.noticed_by_person_id} cross to
     *       Person; {@code Aircraft.aircraft_owner_person_id} crosses to
     *       Person and {@code Aircraft.homebase_id} rides cross-tenant out
     *       of Aircraft into tenant-scoped Location.</li>
     *   <li>Accounting + audit group (S-186):
     *       {@code AircraftReservation.aircraft_id} crosses to cross-tenant
     *       Aircraft; {@code AircraftReservation.pilot_person_id} +
     *       {@code AircraftReservation.second_crew_person_id} +
     *       {@code PlanningDayAssignment.assigned_person_id} cross to
     *       Person; {@code Delivery.recipient_person_id} rides cross-tenant
     *       SET-NULL (Swiss OR Art. 957a frozen-snapshot ride-through);
     *       {@code PersonFlightTimeCredit.person_id} crosses to Person (the
     *       credit is per-person, visible to every club the Person belongs to,
     *       no own club_id);
     *       {@code AuditLog.actor_user_id} rides cross-tenant to historical
     *       User rows (orphan-synthesized when no User in the bundle).</li>
     * </ul>
     *
     * <p>Reference tables, aggregate-internal counter entities, and Club
     * itself must declare empty bypass — a producer bundle widening the
     * set beyond this allow-list is rejected at parse.
     */
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

    /** The canonical cross-tenant allow-list; see {@link #TENANT_BYPASS_ALLOW_LIST}. */
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

    /**
     * Defense-in-depth gate for the S-184 Security plan: only the three
     * cross-tenant FK holders may carry a non-empty
     * {@link EntityPolicy#tenantBypassFks}; every other entity must
     * declare an empty set. A producer bundle widening the allow-list is
     * rejected at parse so a malformed Manifest cannot smuggle a
     * cross-tenant bypass past S-141.
     */
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

package ch.alpenflight.migration.bundle;

import static ch.alpenflight.migration.bundle.TenantBypassGrant.CrossTenantReason.AIRCRAFT_SHARED_BY_EVERY_CLUB_THAT_OPERATES_IT;
import static ch.alpenflight.migration.bundle.TenantBypassGrant.CrossTenantReason.HISTORICAL_ACTOR_USER_OF_AN_AUDITED_CHANGE_NEVER_ITS_OWNING_TENANT;
import static ch.alpenflight.migration.bundle.TenantBypassGrant.CrossTenantReason.HOMEBASE_LOCATION_OF_A_SHARED_AIRCRAFT;
import static ch.alpenflight.migration.bundle.TenantBypassGrant.CrossTenantReason.PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO;
import static ch.alpenflight.migration.bundle.TenantBypassGrant.CrossTenantReason.RECIPIENT_PERSON_FROZEN_INTO_A_DELIVERED_ACCOUNTING_SNAPSHOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManifestTenantBypassAllowListTest {

    private static final int REVIEWED_CROSS_TENANT_ENTITY_COUNT = 12;

    private static final Map<EntityType, TenantBypassGrant> REVIEWED_GRANTS = reviewedGrants();

    private static Map<EntityType, TenantBypassGrant> reviewedGrants() {
        EnumMap<EntityType, TenantBypassGrant> reviewed = new EnumMap<>(EntityType.class);
        reviewed.put(EntityType.USER, TenantBypassGrant.forOneColumn(
                "person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        reviewed.put(EntityType.PERSON_CLUB, TenantBypassGrant.forOneColumn(
                "person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        reviewed.put(EntityType.PERSON_CATEGORY_ASSIGNMENT, TenantBypassGrant.forOneColumn(
                "person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        reviewed.put(EntityType.AIRCRAFT, new TenantBypassGrant(Map.of(
                "aircraft_owner_person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO,
                "homebase_id", HOMEBASE_LOCATION_OF_A_SHARED_AIRCRAFT)));
        reviewed.put(EntityType.AIRCRAFT_AIRCRAFT_STATE, TenantBypassGrant.forOneColumn(
                "noticed_by_person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        reviewed.put(EntityType.FLIGHT, TenantBypassGrant.forOneColumn(
                "aircraft_id", AIRCRAFT_SHARED_BY_EVERY_CLUB_THAT_OPERATES_IT));
        reviewed.put(EntityType.FLIGHT_CREW, TenantBypassGrant.forOneColumn(
                "person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        reviewed.put(EntityType.AIRCRAFT_RESERVATION, new TenantBypassGrant(Map.of(
                "aircraft_id", AIRCRAFT_SHARED_BY_EVERY_CLUB_THAT_OPERATES_IT,
                "pilot_person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO,
                "second_crew_person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO)));
        reviewed.put(EntityType.PLANNING_DAY_ASSIGNMENT, TenantBypassGrant.forOneColumn(
                "assigned_person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        reviewed.put(EntityType.DELIVERY, TenantBypassGrant.forOneColumn(
                "recipient_person_id", RECIPIENT_PERSON_FROZEN_INTO_A_DELIVERED_ACCOUNTING_SNAPSHOT));
        reviewed.put(EntityType.PERSON_FLIGHT_TIME_CREDIT, TenantBypassGrant.forOneColumn(
                "person_id", PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO));
        reviewed.put(EntityType.AUDIT_LOG, TenantBypassGrant.forOneColumn(
                "actor_user_id", HISTORICAL_ACTOR_USER_OF_AN_AUDITED_CHANGE_NEVER_ITS_OWNING_TENANT));
        return reviewed;
    }

    @Test
    void pinsEveryEntityItsGrantedColumnsAndTheReasonEachColumnLeavesItsOwningTenant() {
        assertThat(Manifest.reviewedCrossTenantGrantsByEntity())
                .containsExactlyInAnyOrderEntriesOf(REVIEWED_GRANTS);
    }

    @Test
    void redsWhenTheShippedGrantCountLeavesTheReviewedCountEvenIfBothTablesAreEditedTogether() {
        assertThat(Manifest.reviewedCrossTenantGrantsByEntity())
                .hasSize(REVIEWED_CROSS_TENANT_ENTITY_COUNT);
        assertThat(Manifest.tenantBypassAllowList())
                .hasSize(REVIEWED_CROSS_TENANT_ENTITY_COUNT);
    }

    @Test
    void rejectsABypassColumnOnAnEntityTheReviewedTableNeverGranted() {
        for (EntityType ungranted : EntityType.values()) {
            if (REVIEWED_GRANTS.containsKey(ungranted)) {
                continue;
            }
            Map<EntityType, EntityPolicy> policies =
                    fullPortForEveryEntityExcept(ungranted, Set.of("person_id"));
            assertThatThrownBy(() -> new Manifest(1, policies, Map.of()))
                    .as("%s carries no reviewed cross-tenant grant", ungranted)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(ungranted.name());
        }
    }

    @Test
    void rejectsAColumnTheGrantedEntityWasNeverGrantedSoAWidenedGrantIsADeliberateEdit() {
        Map<EntityType, EntityPolicy> policies =
                fullPortForEveryEntityExcept(EntityType.AUDIT_LOG, Set.of("tenant_club_id"));
        assertThatThrownBy(() -> new Manifest(1, policies, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDIT_LOG")
                .hasMessageContaining("tenant_club_id");
    }

    private static Map<EntityType, EntityPolicy> fullPortForEveryEntityExcept(
            EntityType declaringEntity, Set<String> declaredBypassColumns) {
        EnumMap<EntityType, EntityPolicy> policies = new EnumMap<>(EntityType.class);
        for (EntityType entity : EntityType.values()) {
            policies.put(entity, new EntityPolicy(
                    EntityPolicy.PortPolicy.FULL_PORT,
                    EntityPolicy.TombstonePolicy.PORT_ALL,
                    Set.of(),
                    List.of("id")));
        }
        policies.put(declaringEntity, new EntityPolicy(
                EntityPolicy.PortPolicy.FULL_PORT,
                EntityPolicy.TombstonePolicy.PORT_ALL,
                declaredBypassColumns,
                List.of("id")));
        return policies;
    }
}

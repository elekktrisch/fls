package ch.alpenflight.migration.bundle;

import java.util.Map;
import java.util.Set;

public record TenantBypassGrant(
        Map<String, CrossTenantReason> crossTenantReasonByForeignKeyColumn) {

    public TenantBypassGrant {
        if (crossTenantReasonByForeignKeyColumn == null
                || crossTenantReasonByForeignKeyColumn.isEmpty()) {
            throw new IllegalArgumentException(
                    "A cross-tenant grant must name at least one foreign-key column "
                            + "and the reason that column may leave its owning tenant");
        }
        crossTenantReasonByForeignKeyColumn = Map.copyOf(crossTenantReasonByForeignKeyColumn);
    }

    public static TenantBypassGrant forOneColumn(String column, CrossTenantReason reason) {
        return new TenantBypassGrant(Map.of(column, reason));
    }

    public Set<String> grantedForeignKeyColumns() {
        return crossTenantReasonByForeignKeyColumn.keySet();
    }

    public enum CrossTenantReason {
        PERSON_SHARED_BY_EVERY_CLUB_THE_PERSON_BELONGS_TO,
        AIRCRAFT_SHARED_BY_EVERY_CLUB_THAT_OPERATES_IT,
        HOMEBASE_LOCATION_OF_A_SHARED_AIRCRAFT,
        RECIPIENT_PERSON_FROZEN_INTO_A_DELIVERED_ACCOUNTING_SNAPSHOT,
        HISTORICAL_ACTOR_USER_OF_AN_AUDITED_CHANGE_NEVER_ITS_OWNING_TENANT,
    }
}

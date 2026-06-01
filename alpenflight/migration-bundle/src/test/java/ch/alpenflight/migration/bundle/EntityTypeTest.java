package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntityTypeTest {

    @Test
    void onlyTenantScopedSharedMasterdataFansOut() {
        Set<EntityType> fanOut = EnumSet.noneOf(EntityType.class);
        for (EntityType type : EntityType.values()) {
            if (type.fansOut()) {
                fanOut.add(type);
            }
        }
        assertThat(fanOut)
                .as("LOCATION (first fan-out entity) + its INOUTBOUND_POINT child "
                        + "carry club_id and fan out per club; CLUB / COUNTRY / "
                        + "CLUB_STATE / identity entities must NOT (they keep the "
                        + "2-column id-map format)")
                .containsExactlyInAnyOrder(
                        EntityType.LOCATION, EntityType.INOUTBOUND_POINT);
    }
}

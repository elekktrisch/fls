package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ManifestTenantBypassAllowListTest {

    @Test
    void pinsTheExactCrossTenantEntitySetSoAnyChangeIsADeliberateEdit() {
        assertThat(Manifest.tenantBypassAllowList()).containsExactlyInAnyOrder(
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
    }
}

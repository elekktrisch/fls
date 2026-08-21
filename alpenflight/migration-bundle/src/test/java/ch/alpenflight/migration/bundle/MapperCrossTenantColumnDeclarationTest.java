package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MapperCrossTenantColumnDeclarationTest {

    @Test
    void everyDeclaredCrossTenantColumnIsAColumnTheMapperActuallyPutsOnTheWire() {
        Map<String, Set<String>> phantomColumnsByMapper = new TreeMap<>();
        for (Mapper mapper : KnownMappers.all()) {
            Set<String> wire = new TreeSet<>(Arrays.asList(mapper.wireColumns()));
            Set<String> phantom = new TreeSet<>(mapper.crossTenantForeignKeyColumns());
            phantom.removeAll(wire);
            if (!phantom.isEmpty()) {
                phantomColumnsByMapper.put(mapper.entityType().name(), phantom);
            }
        }

        assertThat(phantomColumnsByMapper)
                .as("a cross-tenant declaration naming a column the mapper never emits is a "
                        + "dormant declaration: it can never be scored against the reviewed "
                        + "grant, because no bundle row carries that column")
                .isEmpty();
    }

    @Test
    void everyMapperOfAnEntityOnTheReviewedGrantTableDeclaresItsCrossTenantColumns() {
        Set<String> grantedButSilent = new TreeSet<>();
        for (Mapper mapper : KnownMappers.all()) {
            boolean granted = Manifest.reviewedCrossTenantGrantsByEntity()
                    .containsKey(mapper.entityType());
            if (granted && mapper.crossTenantForeignKeyColumns().isEmpty()) {
                grantedButSilent.add(mapper.entityType().name());
            }
        }

        assertThat(grantedButSilent)
                .as("an entity the reviewed grant table names as cross-tenant, whose mapper "
                        + "declares nothing, hands the manifest allow-list check an empty set: "
                        + "the check then passes because nothing reached it, not because "
                        + "nothing is wrong")
                .isEmpty();
    }

    @Test
    void noMapperDeclaresACrossTenantColumnOutsideItsReviewedGrant() {
        Map<String, Set<String>> ungrantedColumnsByMapper = new TreeMap<>();
        for (Mapper mapper : KnownMappers.all()) {
            Set<String> declared = new TreeSet<>(mapper.crossTenantForeignKeyColumns());
            if (declared.isEmpty()) {
                continue;
            }
            TenantBypassGrant grant =
                    Manifest.reviewedCrossTenantGrantsByEntity().get(mapper.entityType());
            if (grant == null) {
                ungrantedColumnsByMapper.put(mapper.entityType().name(), declared);
                continue;
            }
            declared.removeAll(grant.grantedForeignKeyColumns());
            if (!declared.isEmpty()) {
                ungrantedColumnsByMapper.put(mapper.entityType().name(), declared);
            }
        }

        assertThat(ungrantedColumnsByMapper)
                .as("widening what the producer sends across a tenant boundary is a reviewed "
                        + "edit: add the column and its CrossTenantReason to the grant table "
                        + "in Manifest first")
                .isEmpty();
    }
}

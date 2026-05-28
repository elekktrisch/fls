package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import org.junit.jupiter.api.Test;

class CountryMapperTest {

    private final CountryMapper mapper = new CountryMapper();

    @Test
    void exposesCountryEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.COUNTRY);
    }

    @Test
    void columnsCoverPkAndLegacyIntIdHook() {
        assertThat(mapper.columns())
                .as("Country is a SYSTEM_GLOBAL ref; legacy_int_id must be present "
                        + "as the cutover resolution surface per S-012.")
                .contains("id", "legacy_int_id");
    }

    @Test
    void columnsReturnIsCallerSafeMutation() {
        String[] first = mapper.columns();
        first[0] = "MUTATED";
        assertThat(mapper.columns()[0])
                .as("Mapper.columns() invariant — callers must not be able to mutate "
                        + "the shared column list.")
                .isEqualTo("id");
    }
}

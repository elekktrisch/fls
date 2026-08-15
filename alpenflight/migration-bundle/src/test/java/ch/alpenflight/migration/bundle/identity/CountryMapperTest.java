package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.util.Map;
import java.util.UUID;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class CountryMapperTest extends AbstractMapperContractTest<CountryMapper> {

    private final CountryMapper mapper = new CountryMapper();

    @Override
    protected CountryMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        return Map.of(
                "CountryId", new UUID(
                        faker.random().nextLong(), faker.random().nextLong()).toString(),
                "CountryCodeIso2", faker.country().countryCode2().toUpperCase());
    }

    @Test
    void exposesCountryEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.COUNTRY);
    }

    @Test
    void hasNoForeignKeysAsSystemGlobalRef() {
        assertThat(mapper.foreignKeyTargets())
                .as("Country is SYSTEM_GLOBAL — V2 seeds resolve by iso2_code, "
                        + "no per-bundle FK dependency")
                .isEmpty();
    }
}

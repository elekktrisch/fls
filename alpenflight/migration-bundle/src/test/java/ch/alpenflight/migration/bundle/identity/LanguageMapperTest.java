package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class LanguageMapperTest extends AbstractMapperContractTest<LanguageMapper> {

    private final LanguageMapper mapper = new LanguageMapper();

    @Override
    protected LanguageMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        return Map.of(
                "LanguageId", faker.random().nextInt(1, 1_000_000),
                "LanguageKey", faker.expression("#{regexify '[A-Za-z]{2}'}"));
    }

    @Test
    void exposesLanguageEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.LANGUAGE);
    }

    @Test
    void hasNoForeignKeysAsSystemGlobalRef() {
        assertThat(mapper.foreignKeyTargets()).isEmpty();
    }
}

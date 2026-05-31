package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.ForeignKeyOrphanPlan.Check;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForeignKeyOrphanPlanTest {

    @Test
    void oneCheckPerDeclaredForeignKeyTarget() {
        List<Mapper> mappers = List.of(
                new FakeMapper(EntityType.USER, new String[] {"legacy_guid"},
                        List.of(EntityType.CLUB, EntityType.COUNTRY)),
                new FakeMapper(EntityType.CLUB, new String[] {"legacy_guid"},
                        List.of()));

        ForeignKeyOrphanPlan plan = ForeignKeyOrphanPlan.from(mappers);

        assertThat(plan.checks()).containsExactly(
                new Check(EntityType.USER, EntityType.CLUB),
                new Check(EntityType.USER, EntityType.COUNTRY));
    }

    @Test
    void mapperWithoutForeignKeysContributesNoChecks() {
        ForeignKeyOrphanPlan plan = ForeignKeyOrphanPlan.from(List.of(
                new FakeMapper(EntityType.COUNTRY, new String[] {"legacy_guid"}, List.of())));
        assertThat(plan.checks()).isEmpty();
    }
}

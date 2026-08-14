package ch.alpenflight.migration.bundle;

import java.util.ArrayList;
import java.util.List;

public record ForeignKeyOrphanPlan(List<Check> checks) {

    public record Check(EntityType source, EntityType target) {
    }

    public static ForeignKeyOrphanPlan from(List<Mapper> mappers) {
        List<Check> checks = new ArrayList<>();
        for (Mapper mapper : mappers) {
            for (EntityType target : mapper.foreignKeys()) {
                checks.add(new Check(mapper.entityType(), target));
            }
        }
        return new ForeignKeyOrphanPlan(List.copyOf(checks));
    }
}

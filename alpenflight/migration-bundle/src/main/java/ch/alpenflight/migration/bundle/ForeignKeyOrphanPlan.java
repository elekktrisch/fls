package ch.alpenflight.migration.bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * The FK-orphan walk plan, derived purely from each mapper's declared
 * {@link Mapper#foreignKeys()}. Deliberately sourced from the mappers, not
 * from a live {@code information_schema} walk: {@code foreignKeys()} is the
 * authoritative declared set the ArchUnit ingest-order rule already trusts, it
 * omits the self-FKs the two-pass UPDATE owns, and it routes cross-tenant FKs
 * through the per-bundle Person sub-map rather than a raw DB constraint. The
 * harness executes the {@code count(*)} of unresolved children per check; this
 * class only computes which checks to run, so it is unit-testable without a
 * container.
 */
public record ForeignKeyOrphanPlan(List<Check> checks) {

    /** One unresolved-child count to run: {@code source}'s FK into {@code target}. */
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

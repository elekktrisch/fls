package ch.alpenflight.migration.bundle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pre-diff coverage gate. Runs before the row-count diff and fails the parity
 * run with precise diagnostics when the fixture under-covers the mapper set —
 * so a green diff can never hide an un-exercised mapper. Pure: the caller
 * supplies the observed seed shape + post-ingest probes; this class owns only
 * the gate semantics + ordering, which makes it unit-testable without a
 * container.
 *
 * <p>Gates:
 * <ol>
 *   <li><strong>Seeded rows.</strong> ≥ 1 row per known mapper — per Club for
 *       tenant-scoped entities, per mapper total for SYSTEM_GLOBAL refs.
 *       Diagnostic: {@code seed gap: <EntityType>[@<ClubId>]}.</li>
 *   <li><strong>Policy ⊆ mappers.</strong> Every {@code Manifest.entityPolicies}
 *       key must be a known mapper.</li>
 *   <li><strong>Unmapped tables empty.</strong> Every
 *       {@link UnmappedTables#REGISTRY} table holds zero rows after ingest.</li>
 * </ol>
 *
 * <p>The sparse-enum per-permitted-value dimension (S-187a AC gate (d)) is
 * fixture-coupled — it lands with the per-group seeder fixtures (deferred
 * follow-up), not here.
 */
public final class ParityCoverageGate {

    private ParityCoverageGate() { }

    /**
     * @param knownMapperEntities          entities backed by a registered mapper.
     * @param systemGlobalEntities         the subset seeded per mapper total, not per Club.
     * @param allClubIds                   the Club ids the fixture seeds.
     * @param seededClubsByEntity          per tenant-scoped entity, the Clubs with ≥ 1 seeded row.
     * @param systemGlobalSeededCounts     per SYSTEM_GLOBAL entity, its total seeded row count.
     * @param manifestPolicyEntities       {@code Manifest.entityPolicies().keySet()}.
     * @param unmappedRegistryTables       {@code UnmappedTables.REGISTRY.keySet()}.
     * @param unmappedTableRowCountsPostIngest  legacy table → row count observed after ingest.
     */
    public record Inputs(
            Set<EntityType> knownMapperEntities,
            Set<EntityType> systemGlobalEntities,
            Set<String> allClubIds,
            Map<EntityType, Set<String>> seededClubsByEntity,
            Map<EntityType, Long> systemGlobalSeededCounts,
            Set<EntityType> manifestPolicyEntities,
            Set<String> unmappedRegistryTables,
            Map<String, Long> unmappedTableRowCountsPostIngest) {
    }

    /** Ordered, deterministic gap diagnostics. Empty list means every gate passed. */
    public static List<String> diagnose(Inputs inputs) {
        List<String> gaps = new ArrayList<>();
        gaps.addAll(seededRowGaps(inputs));
        gaps.addAll(policyWithoutMapperGaps(inputs));
        gaps.addAll(unmappedTableGaps(inputs));
        return gaps;
    }

    private static List<String> seededRowGaps(Inputs inputs) {
        List<String> gaps = new ArrayList<>();
        for (EntityType entity : sortedByOrdinal(inputs.knownMapperEntities())) {
            if (inputs.systemGlobalEntities().contains(entity)) {
                long count = inputs.systemGlobalSeededCounts().getOrDefault(entity, 0L);
                if (count < 1) {
                    gaps.add("seed gap: " + entity);
                }
                continue;
            }
            Set<String> seededClubs = inputs.seededClubsByEntity()
                    .getOrDefault(entity, Set.of());
            for (String clubId : new TreeSet<>(inputs.allClubIds())) {
                if (!seededClubs.contains(clubId)) {
                    gaps.add("seed gap: " + entity + "@" + clubId);
                }
            }
        }
        return gaps;
    }

    private static List<String> policyWithoutMapperGaps(Inputs inputs) {
        List<String> gaps = new ArrayList<>();
        for (EntityType entity : sortedByOrdinal(inputs.manifestPolicyEntities())) {
            if (!inputs.knownMapperEntities().contains(entity)) {
                gaps.add("manifest policy entity without a registered mapper: " + entity);
            }
        }
        return gaps;
    }

    private static List<String> unmappedTableGaps(Inputs inputs) {
        List<String> gaps = new ArrayList<>();
        for (String table : new TreeSet<>(inputs.unmappedRegistryTables())) {
            long count = inputs.unmappedTableRowCountsPostIngest().getOrDefault(table, 0L);
            if (count != 0) {
                gaps.add("unmapped table not empty post-ingest: " + table + " (" + count + ")");
            }
        }
        return gaps;
    }

    private static List<EntityType> sortedByOrdinal(Set<EntityType> entities) {
        return entities.stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
    }
}

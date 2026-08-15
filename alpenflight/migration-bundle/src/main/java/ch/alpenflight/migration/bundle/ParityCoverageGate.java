package ch.alpenflight.migration.bundle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ParityCoverageGate {

    private ParityCoverageGate() { }

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

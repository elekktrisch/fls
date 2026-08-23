package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.migration.bundle.ParityMarkers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ParityDiffEngine {

    private ParityDiffEngine() { }

    public static Map<EntityType, Map<String, Long>> countNewSchemaRowsBeforeIngest(
            Connection postgresConnection, List<Mapper> mappers) throws SQLException {
        Map<EntityType, Map<String, Long>> rowCountsByEntity = new LinkedHashMap<>();
        for (Mapper mapper : mappers) {
            EntityType entity = mapper.entityType();
            if (isRowCountComparable(entity)) {
                rowCountsByEntity.put(entity, countNewSchemaRowsPerClub(
                        postgresConnection, entity));
            }
        }
        return rowCountsByEntity;
    }

    public static DiffOutcome run(
            Connection legacyConnection,
            Connection postgresConnection,
            List<Mapper> mappers,
            Map<EntityType, Map<String, Long>> newSchemaRowsBeforeIngest) throws SQLException {
        List<RowCountDelta> rowCountDeltas = new ArrayList<>();
        Map<EntityType, MapperSentinels> sentinelsByEntity = new LinkedHashMap<>();
        for (Mapper mapper : mappers) {
            EntityType entity = mapper.entityType();
            sentinelsByEntity.put(entity, sentinelsFor(mapper));
            if (!isRowCountComparable(entity)) {
                continue;
            }
            Map<String, Long> producedLegacyRows = countRowsPerClub(
                    legacyConnection,
                    "SELECT ClubId, COUNT(*) FROM ("
                            + MapperLegacyBindings.selectForProducer(entity)
                            + ") AS rows_the_producer_emits GROUP BY ClubId");
            Map<String, Long> ingestedRows = subtractRowsPresentBeforeIngest(
                    countNewSchemaRowsPerClub(postgresConnection, entity),
                    newSchemaRowsBeforeIngest.getOrDefault(entity, Map.of()));
            rowCountDeltas.addAll(diffRowCounts(entity, producedLegacyRows, ingestedRows));
        }
        rowCountDeltas.sort(Comparator
                .comparing((RowCountDelta delta) -> delta.entity().name())
                .thenComparing(RowCountDelta::clubId));
        return new DiffOutcome(rowCountDeltas, sentinelsByEntity);
    }

    private static boolean isRowCountComparable(EntityType entity) {
        return MapperLegacyBindings.isRegistered(entity)
                && MapperLegacyBindings.portPolicy(entity)
                        != MapperLegacyBindings.PortPolicy.SYSTEM_GLOBAL;
    }

    private static Map<String, Long> countNewSchemaRowsPerClub(
            Connection postgresConnection, EntityType entity) throws SQLException {
        String clubColumn = clubColumnFor(entity);
        return countRowsPerClub(
                postgresConnection,
                "SELECT " + clubColumn + ", COUNT(*) FROM "
                        + MapperLegacyBindings.newSchemaTable(entity)
                        + " GROUP BY " + clubColumn);
    }

    private static Map<String, Long> subtractRowsPresentBeforeIngest(
            Map<String, Long> afterIngest, Map<String, Long> beforeIngest) {
        Map<String, Long> ingestedOnly = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : afterIngest.entrySet()) {
            long ingested = entry.getValue()
                    - beforeIngest.getOrDefault(entry.getKey(), 0L);
            if (ingested != 0L) {
                ingestedOnly.put(entry.getKey(), ingested);
            }
        }
        return ingestedOnly;
    }

    private static String clubColumnFor(EntityType entity) {
        return entity == EntityType.CLUB ? "id" : "club_id";
    }

    private static Map<String, Long> countRowsPerClub(Connection connection, String sql)
            throws SQLException {
        Map<String, Long> rowCountByClub = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String clubId = rs.getString(1);
                long count = rs.getLong(2);
                rowCountByClub.put(
                        clubId == null ? "" : clubId.toLowerCase(Locale.ROOT),
                        count);
            }
        }
        return rowCountByClub;
    }

    private static List<RowCountDelta> diffRowCounts(
            EntityType entity,
            Map<String, Long> legacyByClub,
            Map<String, Long> newByClub) {
        List<RowCountDelta> deltas = new ArrayList<>();
        java.util.Set<String> allClubs = new java.util.LinkedHashSet<>();
        allClubs.addAll(legacyByClub.keySet());
        allClubs.addAll(newByClub.keySet());
        for (String clubId : allClubs) {
            long legacy = legacyByClub.getOrDefault(clubId, 0L);
            long fresh = newByClub.getOrDefault(clubId, 0L);
            if (legacy != fresh) {
                deltas.add(new RowCountDelta(entity, clubId, legacy, fresh));
            }
        }
        return deltas;
    }

    private static MapperSentinels sentinelsFor(Mapper mapper) {
        Set<String> sentinelColumns = ParityMarkers.sentinels(mapper.getClass());
        Set<String> ignoredColumns = ParityMarkers.ignored(mapper.getClass());
        return new MapperSentinels(sentinelColumns, ignoredColumns);
    }

    public record RowCountDelta(EntityType entity, String clubId, long legacyCount, long newCount) {
    }

    public record MapperSentinels(Set<String> sentinels, Set<String> ignored) {
    }

    public record DiffOutcome(
            List<RowCountDelta> rowCountDeltas,
            Map<EntityType, MapperSentinels> sentinelsByEntity) {

        public boolean passed() {
            return rowCountDeltas.isEmpty();
        }

        public int totalDeltas() {
            return rowCountDeltas.size();
        }
    }
}

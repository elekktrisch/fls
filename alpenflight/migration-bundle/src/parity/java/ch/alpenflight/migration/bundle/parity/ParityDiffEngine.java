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

    public static DiffOutcome run(
            Connection legacyConnection,
            Connection postgresConnection,
            List<Mapper> mappers) throws SQLException {
        List<RowCountDelta> rowCountDeltas = new ArrayList<>();
        Map<EntityType, MapperSentinels> sentinelsByEntity = new LinkedHashMap<>();
        for (Mapper mapper : mappers) {
            EntityType entity = mapper.entityType();
            sentinelsByEntity.put(entity, sentinelsFor(mapper));
            if (!MapperLegacyBindings.isRegistered(entity)) {
                continue;
            }
            if (MapperLegacyBindings.portPolicy(entity)
                    == MapperLegacyBindings.PortPolicy.SYSTEM_GLOBAL) {
                continue;
            }
            String legacyTable = legacyTableFor(entity);
            String newTable = MapperLegacyBindings.newSchemaTable(entity);
            Map<String, Long> legacyRows = countRowsPerClub(
                    legacyConnection,
                    "SELECT ClubId, COUNT(*) FROM " + legacyTable + " GROUP BY ClubId");
            Map<String, Long> newRows = countRowsPerClub(
                    postgresConnection,
                    "SELECT " + clubColumnFor(entity) + ", COUNT(*) FROM " + newTable
                            + " GROUP BY " + clubColumnFor(entity));
            rowCountDeltas.addAll(diffRowCounts(entity, legacyRows, newRows));
        }
        rowCountDeltas.sort(Comparator
                .comparing((RowCountDelta delta) -> delta.entity().name())
                .thenComparing(RowCountDelta::clubId));
        return new DiffOutcome(rowCountDeltas, sentinelsByEntity);
    }

    private static String legacyTableFor(EntityType entity) {
        return switch (entity) {
            case CLUB -> "Clubs";
            case USER -> "Users";
            default -> throw new IllegalArgumentException(
                    "No legacy table mapping for " + entity + " — extend the switch in S-187a.");
        };
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

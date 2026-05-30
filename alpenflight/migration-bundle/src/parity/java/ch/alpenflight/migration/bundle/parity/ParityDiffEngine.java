package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityMarkers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diff engine — runs after the round-trip lands rows in Postgres. Asserts
 * (this vertical slice):
 *
 * <ul>
 *   <li><strong>Row counts</strong> per {@link EntityType} per {@code Club},
 *       legacy vs new. Mismatch fails the run with the offending pair.</li>
 *   <li><strong>Sentinel</strong> columns (FK / status enum / monetary /
 *       timestamp / generated + explicit {@code @ParitySentinel}) are
 *       enumerated per mapper for {@link ParityReports} consumption — the
 *       sentinel set drives the 1% TABLESAMPLE sweep S-187a adds.</li>
 *   <li><strong>Skip set</strong> ({@code @ParityIgnore} + structural skips
 *       per ADR 0022) is enumerated per mapper for the same purpose; skip-set
 *       wins over sentinel-set on overlap.</li>
 * </ul>
 *
 * <p>The FK orphan walk, sampled-value diff, soft-delete invariant, and
 * producer-drop reconciliation against {@code migration_run.warnings} land
 * at S-187a — they all require the per-entity Hibernate metadata to do
 * properly, which lives in {@code alpenflight/server/}. The vertical slice
 * proves the per-(Club,table) row-count axis.
 */
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
            if (MapperLegacyBindings.insertForConsumer(entity).contains("SYSTEM_GLOBAL_RESOLVE")) {
                continue;
            }
            String legacyTable = legacyTableFor(entity);
            String newTable = MapperLegacyBindings.newSchemaTable(entity);
            Map<String, Long> legacyRows = countRowsPerClub(
                    legacyConnection, "SELECT ClubId, COUNT(*) FROM " + legacyTable
                            + " GROUP BY ClubId");
            Map<String, Long> newRows = countRowsPerClub(
                    postgresConnection, "SELECT " + clubColumnFor(entity)
                            + ", COUNT(*) FROM " + newTable + " GROUP BY "
                            + clubColumnFor(entity));
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
                    "No legacy table mapping for " + entity + " — S-187a extends the switch.");
        };
    }

    private static String clubColumnFor(EntityType entity) {
        return switch (entity) {
            // t_club's tenant column is its own id.
            case CLUB -> "id";
            default -> "club_id";
        };
    }

    private static Map<String, Long> countRowsPerClub(Connection connection, String sql)
            throws SQLException {
        Map<String, Long> rowCountByClub = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String clubId = rs.getString(1);
                long count = rs.getLong(2);
                rowCountByClub.put(clubId == null ? "" : clubId.toLowerCase(java.util.Locale.ROOT),
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

    /** Per-(entity, club) row-count divergence; empty when the round-trip lined up. */
    public record RowCountDelta(EntityType entity, String clubId, long legacyCount, long newCount) {
    }

    /** Per-mapper sentinel + skip enumeration consumed by {@link ParityReports}. */
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

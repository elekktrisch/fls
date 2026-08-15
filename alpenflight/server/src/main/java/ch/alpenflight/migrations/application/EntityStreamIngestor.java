package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import ch.alpenflight.tenancy.provisioning.application.ProvisioningResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.f4b6a3.uuid.UuidCreator;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;
import org.postgresql.copy.CopyManager;
import org.postgresql.jdbc.PgConnection;

final class EntityStreamIngestor {

    private static final String LEGACY_ID_MAP_ENTRY_PREFIX = "legacy_id_map/";
    private static final String LEGACY_ID_MAP_ENTRY_SUFFIX = ".pgcopy";
    private static final Pattern COLUMN_ALLOWLIST = Pattern.compile("^[A-Za-z0-9_]+$");
    private static final int NDJSON_BATCH_SIZE = 4096;
    private static final ObjectMapper JSON = BundleStreamReader.sharedHardenedJsonMapper();

    private final Map<EntityType, Mapper> mappersByType;

    EntityStreamIngestor(List<Mapper> mappers) {
        Map<EntityType, Mapper> byType = new EnumMap<>(EntityType.class);
        for (Mapper mapper : mappers) {
            validateColumnAllowlist(mapper);
            byType.put(mapper.entityType(), mapper);
        }
        this.mappersByType = Map.copyOf(byType);
    }

    void createTemporaryIdMapTables(Connection connection) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            for (EntityType entity : EntityType.values()) {
                statement.execute(
                        "CREATE TEMP TABLE " + LegacyIdMapTables.temporaryTableName(entity)
                                + " " + idMapTableColumns(entity) + " ON COMMIT DROP");
            }
        }
    }

    private static String idMapTableColumns(EntityType entity) {
        if (entity.fansOut()) {
            return "(legacy_guid uuid, club_id uuid, new_uuid uuid NOT NULL, "
                    + "PRIMARY KEY (legacy_guid, club_id))";
        }
        return "(legacy_guid uuid PRIMARY KEY, new_uuid uuid NOT NULL)";
    }

    void seedClubLegacyIdMap(Connection connection,
                             BundleManifest manifest,
                             ProvisioningResult provisioned) throws SQLException {
        Map<String, UUID> provisionedIdByClubKey =
                loadProvisionedClubIdsByKey(connection, provisioned.clubIds());
        String table = LegacyIdMapTables.temporaryTableName(EntityType.CLUB);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + table + " (legacy_guid, new_uuid) VALUES (?, ?)")) {
            for (BundleManifest.ClubDeclaration club : manifest.clubs()) {
                UUID provisionedId = provisionedIdByClubKey.get(club.clubKey());
                if (provisionedId == null) {
                    throw new IllegalStateException(
                            "Provisioning yielded no Club for manifest clubKey " + club.clubKey());
                }
                insert.setObject(1, club.legacyClubId());
                insert.setObject(2, provisionedId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("ANALYZE " + table);
        }
    }

    private static Map<String, UUID> loadProvisionedClubIdsByKey(Connection connection,
                                                                 List<UUID> clubIds)
            throws SQLException {
        Map<String, UUID> idByClubKey = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, club_key FROM t_club WHERE id = ANY(?)")) {
            select.setArray(1, connection.createArrayOf("uuid", clubIds.toArray()));
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    idByClubKey.put(rows.getString("club_key"), rows.getObject("id", UUID.class));
                }
            }
        }
        return idByClubKey;
    }

    void copyLegacyIdMap(Connection connection,
                         String tarEntryName,
                         InputStream tarStream) throws SQLException {
        String entitySuffix = tarEntryName.substring(
                LEGACY_ID_MAP_ENTRY_PREFIX.length(),
                tarEntryName.length() - LEGACY_ID_MAP_ENTRY_SUFFIX.length());
        String table = "legacy_id_map_" + entitySuffix;
        String columnList = idMapCopyColumns(entitySuffix);
        try {
            PgConnection pg = connection.unwrap(PgConnection.class);
            CopyManager copy = pg.getCopyAPI();
            copy.copyIn("COPY " + table + " (" + columnList + ") FROM STDIN BINARY",
                    new BundleStreamReader.NonClosingInputStream(tarStream));
        } catch (IOException ioFailure) {
            throw new SQLException("I/O failure during COPY of " + table, ioFailure);
        }
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("ANALYZE " + table);
        }
    }

    private static String idMapCopyColumns(String entitySuffix) {
        EntityType entity;
        try {
            entity = EntityType.valueOf(entitySuffix.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException notAnEntity) {
            return "legacy_guid, new_uuid";
        }
        return entity.fansOut()
                ? "legacy_guid, club_id, new_uuid"
                : "legacy_guid, new_uuid";
    }

    Mapper mapperFor(EntityType entityType) {
        Mapper mapper = mappersByType.get(entityType);
        if (mapper == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.MAPPER_NOT_AVAILABLE,
                    "No mapper registered for entity " + entityType);
        }
        return mapper;
    }

    void ingestEntityNdjson(Connection connection,
                            Mapper mapper,
                            InputStream tarStream,
                            ForeignKeyResolver foreignKeyResolver,
                            ReferenceLookupResolver referenceLookupResolver)
            throws SQLException, IOException {
        String insert = insertStatementFor(mapper.entityType());
        boolean mintsSurrogateId =
                mintsSurrogateId(mapper.entityType(), mapper.columns());
        int surrogateIdPosition = mapper.columns().length + 1;
        List<String> deferredSelfFkColumns = mapper.deferredSelfFkColumns();
        DeferredSelfFkUpdates deferredUpdates = deferredSelfFkColumns.isEmpty()
                ? null
                : new DeferredSelfFkUpdates(mapper.entityType(), deferredSelfFkColumns);
        try (PreparedStatement ps = connection.prepareStatement(insert);
                BundleStreamReader.NonClosingBufferedReader lines =
                        BundleStreamReader.NonClosingBufferedReader.of(tarStream)) {
            String line;
            int batched = 0;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode parsed;
                try {
                    parsed = JSON.readTree(line);
                } catch (IOException parseFailure) {
                    throw new BundleIngestException(
                            BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                            "NDJSON parse failed on " + mapper.entityType(), parseFailure);
                }
                if (!(parsed instanceof ObjectNode row)) {
                    throw new BundleIngestException(
                            BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                            "NDJSON row on " + mapper.entityType()
                                    + " must be a JSON object, got " + parsed.getNodeType());
                }
                if (mapper.entityType() == EntityType.CLUB) {
                    foreignKeyResolver.rewriteSelfId(
                            EntityType.CLUB, WIRE_LEGACY_GUID_COLUMN, row);
                }
                foreignKeyResolver.rewriteForeignKeys(mapper, row);
                referenceLookupResolver.rewriteReferenceLookups(mapper, row);
                if (deferredUpdates != null) {
                    deferredUpdates.captureAndNull(row);
                }
                mapper.readEntity(row, ps);
                if (mintsSurrogateId) {
                    ps.setObject(surrogateIdPosition, UuidCreator.getTimeOrderedEpoch());
                }
                ps.addBatch();
                batched++;
                if (batched >= NDJSON_BATCH_SIZE) {
                    ps.executeBatch();
                    batched = 0;
                }
            }
            if (batched > 0) {
                ps.executeBatch();
            }
        }
        if (deferredUpdates != null) {
            deferredUpdates.applyAfterAllRowsInserted(connection);
        }
    }

    private static final class DeferredSelfFkUpdates {

        private final EntityType entityType;
        private final List<String> columns;
        private final List<Object[]> edges = new java.util.ArrayList<>();

        DeferredSelfFkUpdates(EntityType entityType, List<String> columns) {
            this.entityType = entityType;
            this.columns = List.copyOf(columns);
        }

        void captureAndNull(ObjectNode row) {
            JsonNode idNode = row.get(WIRE_LEGACY_GUID_COLUMN);
            for (String column : columns) {
                JsonNode value = row.get(column);
                if (value == null || value.isNull()) {
                    continue;
                }
                if (idNode == null || idNode.isNull()) {
                    throw new BundleIngestException(
                            BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                            "Deferred self-FK row on " + entityType + " has a "
                                    + column + " but no " + WIRE_LEGACY_GUID_COLUMN + " id");
                }
                edges.add(new Object[] {
                        column, UUID.fromString(idNode.asText()), UUID.fromString(value.asText())});
                row.putNull(column);
            }
        }

        void applyAfterAllRowsInserted(Connection connection) throws SQLException {
            if (edges.isEmpty()) {
                return;
            }
            String table = destinationTableFor(entityType);
            for (String column : columns) {
                String update = "UPDATE " + table + " SET " + column
                        + " = ? WHERE " + DESTINATION_ID_COLUMN + " = ?";
                try (PreparedStatement ps = connection.prepareStatement(update)) {
                    int batched = 0;
                    for (Object[] edge : edges) {
                        if (!column.equals(edge[0])) {
                            continue;
                        }
                        ps.setObject(1, edge[2]);
                        ps.setObject(2, edge[1]);
                        ps.addBatch();
                        batched++;
                    }
                    if (batched > 0) {
                        ps.executeBatch();
                    }
                }
            }
        }
    }

    static String destinationTableFor(EntityType entityType) {
        return "t_" + entityType.temporaryTableSuffix();
    }

    private static String buildInsertStatement(EntityType entityType, String[] destinationColumns) {
        String insert = "INSERT INTO " + destinationTableFor(entityType) + " ("
                + String.join(", ", destinationColumns) + ") VALUES ("
                + "?,".repeat(destinationColumns.length - 1) + "?)";
        if (entityType != EntityType.CLUB) {
            return insert;
        }
        StringJoiner assignments = new StringJoiner(", ");
        for (String column : destinationColumns) {
            if (DESTINATION_ID_COLUMN.equals(column)) {
                continue;
            }
            assignments.add(column + " = EXCLUDED." + column);
        }
        return insert + " ON CONFLICT (" + DESTINATION_ID_COLUMN + ") DO UPDATE SET " + assignments;
    }

    String insertStatementFor(EntityType entityType) {
        return buildInsertStatement(entityType,
                destinationColumnNames(entityType, mapperFor(entityType).columns()));
    }

    private static String[] destinationColumnNames(EntityType entityType, String[] wireColumns) {
        if (entityType.fansOut()) {
            return wireColumns.clone();
        }
        if (mintsSurrogateId(entityType, wireColumns)) {
            String[] destinationColumns = new String[wireColumns.length + 1];
            System.arraycopy(wireColumns, 0, destinationColumns, 0, wireColumns.length);
            destinationColumns[wireColumns.length] = DESTINATION_ID_COLUMN;
            return destinationColumns;
        }
        String[] destinationColumns = new String[wireColumns.length];
        for (int i = 0; i < wireColumns.length; i++) {
            destinationColumns[i] = WIRE_LEGACY_GUID_COLUMN.equals(wireColumns[i])
                    ? DESTINATION_ID_COLUMN
                    : wireColumns[i];
        }
        return destinationColumns;
    }

    private static final String WIRE_LEGACY_GUID_COLUMN = "legacy_guid";
    private static final String DESTINATION_ID_COLUMN = "id";

    private static boolean mintsSurrogateId(EntityType entityType, String[] wireColumns) {
        if (entityType.fansOut()) {
            return false;
        }
        for (String column : wireColumns) {
            if (WIRE_LEGACY_GUID_COLUMN.equals(column)) {
                return false;
            }
        }
        return true;
    }

    private static void validateColumnAllowlist(Mapper mapper) {
        for (String column : mapper.columns()) {
            assertSafeIdentifier(mapper, column);
        }
        for (String column : mapper.deferredSelfFkColumns()) {
            assertSafeIdentifier(mapper, column);
        }
    }

    private static void assertSafeIdentifier(Mapper mapper, String column) {
        if (column == null || !COLUMN_ALLOWLIST.matcher(column).matches()) {
            throw new IllegalStateException(
                    "Mapper " + mapper.entityType() + " column "
                            + column + " violates [A-Za-z0-9_]+ allowlist — "
                            + "INSERT-string interpolation requires a safe identifier");
        }
    }
}

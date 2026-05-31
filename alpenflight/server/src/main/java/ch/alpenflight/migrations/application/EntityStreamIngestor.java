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
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.postgresql.copy.CopyManager;
import org.postgresql.jdbc.PgConnection;

/**
 * Streaming ingest worker for the per-entity payload streams the bundle
 * carries — the {@code legacy_id_map_*.pgcopy} COPY streams and the
 * {@code <EntityType>.ndjson} INSERT streams.
 *
 * <p>Validates the column allow-list at construction time so a mapper
 * smuggling a column name outside {@code [A-Za-z0-9_]} fails Spring boot
 * (visible at deploy, not at first user request).
 *
 * <p>Package-private constructor: only callers inside
 * {@code ch.alpenflight.migrations.application} construct it.
 * Structural isolation guards against a future cron / batch job wiring
 * past the orchestrator's principal-owns-upload check.
 */
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
                                + " (legacy_guid uuid PRIMARY KEY, new_uuid uuid NOT NULL) "
                                + "ON COMMIT DROP");
            }
        }
    }

    void seedClubLegacyIdMap(Connection connection,
                             BundleManifest manifest,
                             ProvisioningResult provisioned) throws SQLException {
        String table = LegacyIdMapTables.temporaryTableName(EntityType.CLUB);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + table + " (legacy_guid, new_uuid) VALUES (?, ?)")) {
            List<UUID> clubIds = provisioned.clubIds();
            for (int i = 0; i < manifest.clubs().size(); i++) {
                insert.setObject(1, manifest.clubs().get(i).legacyClubId());
                insert.setObject(2, clubIds.get(i));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("ANALYZE " + table);
        }
    }

    void copyLegacyIdMap(Connection connection,
                         String tarEntryName,
                         InputStream tarStream) throws SQLException {
        String entitySuffix = tarEntryName.substring(
                LEGACY_ID_MAP_ENTRY_PREFIX.length(),
                tarEntryName.length() - LEGACY_ID_MAP_ENTRY_SUFFIX.length());
        String table = "legacy_id_map_" + entitySuffix;
        try {
            PgConnection pg = connection.unwrap(PgConnection.class);
            CopyManager copy = pg.getCopyAPI();
            copy.copyIn("COPY " + table + " FROM STDIN BINARY",
                    new BundleStreamReader.NonClosingInputStream(tarStream));
        } catch (IOException ioFailure) {
            throw new SQLException("I/O failure during COPY of " + table, ioFailure);
        }
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("ANALYZE " + table);
        }
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
                            ForeignKeyResolver foreignKeyResolver) throws SQLException, IOException {
        String[] columns = mapper.columns();
        String[] destinationColumns = destinationColumnNames(columns);
        String insert = "INSERT INTO " + destinationTableFor(mapper.entityType()) + " ("
                + String.join(", ", destinationColumns) + ") VALUES ("
                + "?,".repeat(columns.length - 1) + "?)";
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
                // Translate legacy GUIDs in mapper.foreignKeys() targets to
                // new-stack UUIDs via legacy_id_map_<entity>. The maps are
                // seeded upstream — SYSTEM_GLOBAL_RESOLVE entries by the
                // bundle's legacy_id_map/<entity>.pgcopy tar entries,
                // FULL_PORT CLUB by seedClubLegacyIdMap.
                foreignKeyResolver.rewriteForeignKeys(mapper, row);
                mapper.readEntity(row, ps);
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
    }

    static String destinationTableFor(EntityType entityType) {
        return "t_" + entityType.temporaryTableSuffix();
    }

    /**
     * Maps the mapper's wire-format column names to destination-table
     * column names. The producer emits {@code legacy_guid} as the
     * carrier for the destination's {@code id} per ADR 0019 (legacy GUID
     * preservation); the alias lives at the orchestrator boundary so
     * mappers stay symmetric between producer + consumer halves and the
     * subset-coverage test ({@code MapperVsSchemaCompatibilityTest})
     * already understands the alias.
     */
    private static String[] destinationColumnNames(String[] wireColumns) {
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

    private static void validateColumnAllowlist(Mapper mapper) {
        for (String column : mapper.columns()) {
            if (column == null || !COLUMN_ALLOWLIST.matcher(column).matches()) {
                throw new IllegalStateException(
                        "Mapper " + mapper.entityType() + " column "
                                + column + " violates [A-Za-z0-9_]+ allowlist — "
                                + "INSERT-string interpolation requires a safe identifier");
            }
        }
    }
}

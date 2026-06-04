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
                                + " " + idMapTableColumns(entity) + " ON COMMIT DROP");
            }
        }
    }

    /**
     * Fan-out entities ({@link EntityType#fansOut()}) record N {@code club_id}-
     * distinct replica ids per shared {@code legacy_guid}, so their id-map table
     * is composite-keyed {@code (legacy_guid, club_id)} (a single-key table could
     * hold only one replica id and the T-07 resolver SELECTs on the composite
     * key). Non-fan-out entities keep the 2-column single-key shape that
     * CLUB / SYSTEM_GLOBAL / identity entities rely on.
     */
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
        // Pair each manifest Club to its provisioning-minted id by club_key, not
        // list position: provisioning's idempotency-replay returns clubs in DB
        // order, so an index pairing could seed a Club against the wrong tenant
        // root (ADR 0008).
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

    /** ProvisioningResult carries only ids; recover each club_key so the manifest can pair on it. */
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
        // The COPY column list is pinned to the table shape so the bundle's
        // pgcopy field order is matched explicitly: a fan-out entity's pgcopy
        // is 3-column (legacy_guid, club_id, new_uuid) targeting the composite
        // table; non-fan-out stays 2-column (legacy_guid, new_uuid). The entity
        // is the tar-entry suffix — fail closed if it is not a known EntityType
        // (an unknown suffix never reaches a valid id-map table anyway).
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

    /**
     * COPY column list for the {@code legacy_id_map_<entity>} table, pinned to
     * the table's shape: a fan-out entity ({@link EntityType#fansOut()}) is the
     * 3-column composite {@code (legacy_guid, club_id, new_uuid)}; everything
     * else stays 2-column {@code (legacy_guid, new_uuid)}. A suffix that is not
     * a known {@link EntityType} (e.g. SYSTEM_GLOBAL) keeps the 2-column shape.
     */
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
        // Aggregate-internal leaf (no legacy_guid): the INSERT appends a trailing
        // surrogate `id` slot the orchestrator binds with a minted UUID v7 after
        // the mapper's positional readEntity binding.
        boolean mintsSurrogateId =
                mintsSurrogateId(mapper.entityType(), mapper.columns());
        int surrogateIdPosition = mapper.columns().length + 1;
        // S-141 two-pass for self-FK columns (tow_flight_id on FLIGHT): the
        // producer emits rows in an arbitrary intra-batch order, so a glider can
        // stream before its tow. INSERT every row with the self-FK NULL, capture
        // the (id, value) deferred edges, then UPDATE them once the whole entity
        // is inserted — robust to any batch order (J-2 T-41).
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
                // CLUB reconciles onto the provisioning-minted t_club (S-141c):
                // rewrite the row's own legacy id to the provisioned id (fail-
                // closed on a miss) so the UPSERT below conflicts on that PK and
                // overlays the legacy columns rather than inserting a second row.
                if (mapper.entityType() == EntityType.CLUB) {
                    foreignKeyResolver.rewriteSelfId(
                            EntityType.CLUB, WIRE_LEGACY_GUID_COLUMN, row);
                }
                // Translate legacy GUIDs in mapper.foreignKeys() targets to
                // new-stack UUIDs via legacy_id_map_<entity>. The maps are
                // seeded upstream — SYSTEM_GLOBAL_RESOLVE entries by the
                // bundle's legacy_id_map/<entity>.pgcopy tar entries,
                // FULL_PORT CLUB by seedClubLegacyIdMap.
                foreignKeyResolver.rewriteForeignKeys(mapper, row);
                // FLIGHT-group reference-lookup columns carry a synthetic
                // new UUID(0, legacyIntId) for a row in the V2/V3 Flyway seed
                // (outside EntityType, so no legacy_id_map). Resolve each to
                // the real seed PK by joining <seedTable>.legacy_int_id; a miss
                // fails closed rather than FK-violating verbatim on INSERT.
                referenceLookupResolver.rewriteReferenceLookups(mapper, row);
                // S-141 two-pass: lift each self-FK value into the deferred set
                // and NULL it on the row so the INSERT writes NULL (the target
                // row may not be inserted yet). The UPDATE pass below applies it
                // after every row of this entity exists.
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
        // Second pass: now that every row of this entity is inserted, resolve the
        // self-FK edges. Runs in the SAME transaction, so a partial first pass
        // never leaves orphan edges (S-141 two-pass).
        if (deferredUpdates != null) {
            deferredUpdates.apply(connection);
        }
    }

    /**
     * Collects the self-FK edges lifted off the INSERT pass and replays them as
     * an {@code UPDATE t_<entity> SET <col> = ? WHERE id = ?} batch after the
     * full INSERT pass (S-141 two-pass). One instance per
     * {@link #ingestEntityNdjson} call — discarded when the stream ends.
     *
     * <p>The {@code id} is read off the row's {@code legacy_guid} wire field:
     * a self-referencing entity (FLIGHT) is non-fan-out FULL_PORT, so the legacy
     * GUID is preserved verbatim as the destination {@code id} (the orchestrator
     * aliases {@code legacy_guid → id} at INSERT time, see
     * {@link #destinationColumnNames}). The captured self-FK value is likewise
     * the already-resolved target id — the UPDATE writes it as-is once the
     * target row exists.
     */
    private static final class DeferredSelfFkUpdates {

        private final EntityType entityType;
        private final List<String> columns;
        private final List<Object[]> edges = new java.util.ArrayList<>();

        DeferredSelfFkUpdates(EntityType entityType, List<String> columns) {
            this.entityType = entityType;
            this.columns = List.copyOf(columns);
        }

        /**
         * Capture each non-null self-FK value as a deferred edge, then NULL the
         * column on the row so the INSERT binds NULL. A null/absent value (the
         * empty-guid no-tow sentinel already collapsed by the producer) carries
         * nothing forward — no edge, the column simply stays NULL.
         */
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

        /**
         * Replay the captured edges as batched per-column UPDATEs. Every target
         * row now exists, so {@code fk_flight_tow_flight_id} resolves regardless
         * of the original batch order. A target genuinely absent from the export
         * (dangling reference) surfaces as the same FK violation it always would
         * — the second pass does NOT mask it, but the producer SELECTs the whole
         * club so this is an ordering fix, not a row-presence one.
         */
        void apply(Connection connection) throws SQLException {
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

    /**
     * CLUB's INSERT is an {@code ON CONFLICT (id) DO UPDATE} so its row
     * reconciles onto the provisioning-minted {@code t_club} (S-141c) instead
     * of colliding. The set-list is exactly the mapper's columns, so the
     * provisioning-owned synthetic columns absent from {@code ClubMapper}
     * ({@code slug}, {@code public_registration_enabled}, {@code deployment_id})
     * are structurally untouchable by the bundle. Column identifiers are the
     * same {@link #validateColumnAllowlist}-gated names as the INSERT.
     */
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

    /** Package-private seam: the INSERT/UPSERT SQL a registered mapper produces. */
    String insertStatementFor(EntityType entityType) {
        return buildInsertStatement(entityType,
                destinationColumnNames(entityType, mapperFor(entityType).columns()));
    }

    /**
     * Maps the mapper's wire-format column names to destination-table
     * column names.
     *
     * <p><strong>Non-fan-out (CLUB / COUNTRY / identity):</strong> the producer
     * emits {@code legacy_guid} as the carrier for the destination's {@code id}
     * per ADR 0019 (legacy GUID preservation); the alias lives at the
     * orchestrator boundary so mappers stay symmetric between producer +
     * consumer halves and the subset-coverage test
     * ({@code MapperVsSchemaCompatibilityTest}) already understands the alias.
     *
     * <p><strong>Fan-out (J-0b — {@link EntityType#fansOut()}):</strong> one
     * shared legacy row fans out to N {@code club_id}-distinct replicas, so the
     * wire carries BOTH a derived per-replica {@code id} AND the shared
     * {@code legacy_guid} as separate fields, and BOTH are real destination
     * columns (the V23/V24 {@code legacy_guid} columns exist). Aliasing
     * {@code legacy_guid → id} here would duplicate {@code id} (PK-colliding the
     * 2nd replica) and drop the shared key — so for fan-out entities the wire
     * column names pass through verbatim.
     */
    private static String[] destinationColumnNames(EntityType entityType, String[] wireColumns) {
        if (entityType.fansOut()) {
            return wireColumns.clone();
        }
        if (mintsSurrogateId(entityType, wireColumns)) {
            // Aggregate-internal LEAF child (AIRCRAFT_AIRCRAFT_STATE): legacy
            // composite PK reshaped to a surrogate `id` (V3) carries no own
            // legacy_guid, so the wire has no id carrier. The orchestrator mints
            // the surrogate at INSERT time per the V3 "application owns identity"
            // rule (no DEFAULT gen_random_uuid). `id` is appended LAST so the
            // mapper's readEntity positional binding (1..n) is untouched and the
            // minted UUID binds at position n+1.
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

    /**
     * Whether the orchestrator mints the surrogate {@code id} for this entity at
     * INSERT time. True for a non-fan-out mapper whose wire columns carry NO
     * {@code legacy_guid} — an aggregate-internal LEAF child (e.g.
     * {@code AIRCRAFT_AIRCRAFT_STATE}) whose legacy composite PK was reshaped to a
     * surrogate {@code id} (V3), so there is no legacy id to alias to {@code id}
     * and the schema has no {@code DEFAULT gen_random_uuid()} (V3 "application
     * owns identity"). The minted value is a UUID v7, matching the JPA
     * {@code @UuidV7} generator the runtime aggregates use.
     */
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
        // Deferred self-FK column names are interpolated into the second-pass
        // UPDATE, so they pass the same identifier gate as the INSERT columns.
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

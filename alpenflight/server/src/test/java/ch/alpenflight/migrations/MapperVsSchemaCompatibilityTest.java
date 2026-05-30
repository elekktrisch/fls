package ch.alpenflight.migrations;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Compatibility gate between bundle mappers and the live Postgres schema —
 * the defence-in-depth promised by S-183's Security plan: every mapper's
 * column list must be a subset of the destination table's columns, and
 * every non-nullable non-defaulted column the FULL_PORT destination
 * requires must be carried by the mapper.
 *
 * <p>Lives in {@code alpenflight/server/} (S-183 pin) so the schema oracle
 * is the live Flyway-migrated database — Hibernate Metadata would only
 * describe Java-mapped tables, and the V2 → V19 schema is broader than the
 * JPA-entity surface. Postgres {@code information_schema.columns} is the
 * source of truth.
 *
 * <p>Parametrised over {@link KnownMappers#all()} (28 mappers). One
 * structural failure batches all per-mapper diagnostics so a reviewer sees
 * every drift at once rather than chasing them one at a time.
 *
 * <p><strong>Wire-format alias.</strong> The mapper's wire-format column
 * {@code legacy_guid} carries the legacy GUID value that becomes the
 * destination row's {@code id} per ADR 0019 (legacy GUID preservation). The
 * subset check treats {@code legacy_guid} as an alias for {@code id} —
 * checking the literal string against the destination would always fail
 * even though the binding is correct.
 *
 * <p><strong>SYSTEM_GLOBAL mappers.</strong> Reference-table mappers
 * (COUNTRY, LANGUAGE, CLUB_STATE, START_TYPE) carry only the (legacy_guid,
 * lookup_key) bundle pair — V2 seed owns the destination rows. They are
 * exempted from the non-nullable-coverage rule; the subset rule still
 * applies (the lookup_key column must exist in the destination).
 *
 * <p><strong>Per-table skip set</strong> (S-187 Design notes):
 * <ul>
 *   <li>{@code legacy_int_id} — shadow column added by S-185 for INT-PK
 *       reference tables; populated by V2 seed, no producer-side counterpart.</li>
 *   <li>{@code operating_club_id} — {@code @TenantId} discriminator
 *       auto-populated by the application layer (ADR 0008).</li>
 *   <li>{@code keycloak_sub} on {@code t_user} — single-writer per ADR 0007;
 *       mapper binds NULL structurally.</li>
 *   <li>{@code legacy_orphan_actor_id} / {@code legacy_actor_user_id} /
 *       {@code actor_kind} on {@code t_audit_log} — V18-only columns
 *       synthesised by the audit-log mapper.</li>
 * </ul>
 */
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class MapperVsSchemaCompatibilityTest {

    private static final String LEGACY_GUID_WIRE_COLUMN = "legacy_guid";
    private static final String DESTINATION_PK_COLUMN = "id";

    private static final Set<EntityType> SYSTEM_GLOBAL_REFERENCE_MAPPERS = Set.of(
            EntityType.COUNTRY,
            EntityType.LANGUAGE,
            EntityType.CLUB_STATE,
            EntityType.START_TYPE);

    private static final Set<String> GLOBAL_SKIP = Set.of(
            "legacy_int_id",
            "operating_club_id");

    private static final Map<String, Set<String>> PER_TABLE_SKIP = Map.of(
            "t_user", Set.of("keycloak_sub"),
            "t_audit_log", Set.of(
                    "legacy_orphan_actor_id",
                    "legacy_actor_user_id",
                    "actor_kind"));

    @Test
    void everyMapperColumnListIsSubsetOfTheSchemaTableAndCoversNonNullables()
            throws SQLException {
        List<String> failures = new java.util.ArrayList<>();
        try (Connection connection = openConnection()) {
            for (Mapper mapper : KnownMappers.all()) {
                String tableName = destinationTableName(mapper.entityType());
                TableSchema tableSchema = loadTableSchema(connection, tableName);
                if (tableSchema.columnsByName().isEmpty()) {
                    failures.add(String.format(
                            "%s: destination table %s has no columns in information_schema "
                                    + "— either the table does not exist yet or the schema is "
                                    + "empty.",
                            mapper.getClass().getSimpleName(), tableName));
                    continue;
                }
                Set<String> mapperColumns = mapperColumnsResolved(mapper);
                checkSubsetOfSchema(mapper, tableName, mapperColumns, tableSchema, failures);
                if (!SYSTEM_GLOBAL_REFERENCE_MAPPERS.contains(mapper.entityType())) {
                    checkNonNullableCoverage(
                            mapper, tableName, mapperColumns, tableSchema,
                            perTableSkip(tableName), failures);
                }
            }
        }
        assertThat(failures)
                .as("MapperVsSchemaCompatibility — every FULL_PORT mapper's columns() "
                        + "must be a subset of its destination table and must cover every "
                        + "non-nullable non-defaulted column not on the skip set. "
                        + "SYSTEM_GLOBAL reference mappers (COUNTRY / LANGUAGE / CLUB_STATE / "
                        + "START_TYPE) carry the subset check only — V2 owns the destination rows.")
                .isEmpty();
    }

    private static Set<String> mapperColumnsResolved(Mapper mapper) {
        Set<String> resolved = new LinkedHashSet<>();
        for (String column : Arrays.asList(mapper.columns())) {
            // legacy_guid is the wire-format name for the destination PK
            // (per ADR 0019 GUID preservation). Translate so the
            // structural subset check sees the destination column name.
            resolved.add(LEGACY_GUID_WIRE_COLUMN.equals(column)
                    ? DESTINATION_PK_COLUMN : column);
        }
        return resolved;
    }

    private static void checkSubsetOfSchema(
            Mapper mapper,
            String tableName,
            Set<String> mapperColumns,
            TableSchema tableSchema,
            List<String> failures) {
        Set<String> extras = new LinkedHashSet<>(mapperColumns);
        extras.removeAll(tableSchema.columnsByName().keySet());
        if (!extras.isEmpty()) {
            failures.add(String.format(
                    "%s declares columns not present in %s: %s. Mapper.columns() (with "
                            + "legacy_guid → id alias) must be a subset of the destination "
                            + "table.",
                    mapper.getClass().getSimpleName(), tableName, extras));
        }
    }

    private static void checkNonNullableCoverage(
            Mapper mapper,
            String tableName,
            Set<String> mapperColumns,
            TableSchema tableSchema,
            Set<String> perTableSkip,
            List<String> failures) {
        Set<String> mustCover = new LinkedHashSet<>();
        for (Map.Entry<String, ColumnInfo> entry : tableSchema.columnsByName().entrySet()) {
            ColumnInfo column = entry.getValue();
            if (column.isNullable()) {
                continue;
            }
            if (column.hasDefault()) {
                continue;
            }
            if (column.isGenerated()) {
                continue;
            }
            if (GLOBAL_SKIP.contains(column.name())) {
                continue;
            }
            if (perTableSkip.contains(column.name())) {
                continue;
            }
            mustCover.add(column.name());
        }
        Set<String> missing = new LinkedHashSet<>(mustCover);
        missing.removeAll(mapperColumns);
        if (!missing.isEmpty()) {
            failures.add(String.format(
                    "%s must bind non-nullable non-defaulted columns of %s: missing %s. "
                            + "Either add them to columns() / readEntity() or add a skip-set "
                            + "entry with rationale.",
                    mapper.getClass().getSimpleName(), tableName, missing));
        }
    }

    private static Set<String> perTableSkip(String tableName) {
        return PER_TABLE_SKIP.getOrDefault(tableName, Set.of());
    }

    /**
     * Convention from the Flyway migration set: {@code t_<lower-snake>} for
     * every domain table. {@link EntityType#temporaryTableSuffix} already
     * computes the lower-snake form (it is used elsewhere for the
     * legacy_id_map table names).
     */
    private static String destinationTableName(EntityType entity) {
        return "t_" + entity.temporaryTableSuffix();
    }

    private static TableSchema loadTableSchema(Connection connection, String tableName)
            throws SQLException {
        String sql = """
                SELECT column_name, is_nullable, column_default, is_generated
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """;
        Map<String, ColumnInfo> columnsByName = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String columnName = rs.getString("column_name").toLowerCase(Locale.ROOT);
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                    String columnDefault = rs.getString("column_default");
                    boolean hasDefault = columnDefault != null && !columnDefault.isBlank();
                    boolean generated = !"NEVER".equalsIgnoreCase(rs.getString("is_generated"));
                    columnsByName.put(columnName,
                            new ColumnInfo(columnName, nullable, hasDefault, generated));
                }
            }
        }
        return new TableSchema(tableName, columnsByName);
    }

    private static Connection openConnection() throws SQLException {
        var pg = SharedPostgresContainer.INSTANCE;
        // The SharedPostgresContainer does NOT auto-run Flyway —
        // @SpringBootTest classes drive it. Run Flyway here directly so
        // the test can stay non-Spring (no application context boot).
        ensureSchemaMigrated(pg.jdbcUrl(), pg.username(), pg.password());
        return DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
    }

    private static void ensureSchemaMigrated(String jdbcUrl, String user, String password) {
        // Idempotent: Flyway sees an existing flyway_schema_history and
        // is a no-op once V1..VN are recorded. First-call invocation
        // drives the initial migrate.
        org.flywaydb.core.Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .placeholders(Map.of(
                        "alpenflight.operator.keycloak_sub",
                        "00000000-0000-0000-0000-0000000000ff"))
                .load()
                .migrate();
    }

    private record TableSchema(String tableName, Map<String, ColumnInfo> columnsByName) {
    }

    private record ColumnInfo(String name, boolean isNullable, boolean hasDefault, boolean isGenerated) {
    }
}

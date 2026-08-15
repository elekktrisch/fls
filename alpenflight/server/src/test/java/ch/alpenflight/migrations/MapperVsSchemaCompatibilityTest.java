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

    private static final String SEED_POPULATED_SHADOW_COLUMN = "legacy_int_id";
    private static final String TENANT_DISCRIMINATOR_POPULATED_BY_THE_APPLICATION_LAYER =
            "operating_club_id";

    private static final Set<String> COLUMNS_POPULATED_OUTSIDE_ANY_MAPPER = Set.of(
            SEED_POPULATED_SHADOW_COLUMN,
            TENANT_DISCRIMINATOR_POPULATED_BY_THE_APPLICATION_LAYER);

    private static final Map<EntityType, String> DESTINATION_TABLE_OVERRIDE = Map.of(
            EntityType.AUDIT_LOG, "t_mutation_audit_event");

    private static final Set<String> KEYCLOAK_OWNED_SINGLE_WRITER_COLUMNS =
            Set.of("keycloak_sub");
    private static final Set<String> COLUMNS_SYNTHESISED_BY_THE_AUDIT_LOG_MAPPER = Set.of(
            "legacy_orphan_actor_id",
            "legacy_actor_user_id",
            "actor_kind");

    private static final Map<String, Set<String>> PER_TABLE_COLUMNS_POPULATED_OUTSIDE_THE_MAPPER =
            Map.of(
                    "t_user", KEYCLOAK_OWNED_SINGLE_WRITER_COLUMNS,
                    "t_mutation_audit_event", COLUMNS_SYNTHESISED_BY_THE_AUDIT_LOG_MAPPER);

    private static final Set<EntityType> MAPPERS_WHOSE_PK_IS_MINTED_AT_INGEST = Set.of(
            EntityType.PERSON_CLUB,
            EntityType.PERSON_CATEGORY_ASSIGNMENT,
            EntityType.AIRCRAFT_AIRCRAFT_STATE);

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
                Set<String> mapperColumns =
                        mapperColumnsWithLegacyGuidResolvedToDestinationPk(mapper);
                checkSubsetOfSchema(mapper, tableName, mapperColumns, tableSchema, failures);
                if (!SYSTEM_GLOBAL_REFERENCE_MAPPERS.contains(mapper.entityType())) {
                    Set<String> perTableSkip = new LinkedHashSet<>(perTableSkip(tableName));
                    if (MAPPERS_WHOSE_PK_IS_MINTED_AT_INGEST.contains(mapper.entityType())) {
                        perTableSkip.add(DESTINATION_PK_COLUMN);
                    }
                    checkNonNullableCoverage(
                            mapper, tableName, mapperColumns, tableSchema,
                            perTableSkip, failures);
                }
            }
        }
        assertThat(failures)
                .as("MapperVsSchemaCompatibility — every FULL_PORT mapper's wireColumns() "
                        + "must be a subset of its destination table and must cover every "
                        + "non-nullable non-defaulted column not on the skip set. "
                        + "SYSTEM_GLOBAL reference mappers (COUNTRY / LANGUAGE / CLUB_STATE / "
                        + "START_TYPE) carry the subset check only — V2 owns the destination rows.")
                .isEmpty();
    }

    private static Set<String> mapperColumnsWithLegacyGuidResolvedToDestinationPk(Mapper mapper) {
        Set<String> resolved = new LinkedHashSet<>();
        for (String column : Arrays.asList(mapper.wireColumns())) {
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
                    "%s declares columns not present in %s: %s. Mapper.wireColumns() (with "
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
            if (COLUMNS_POPULATED_OUTSIDE_ANY_MAPPER.contains(column.name())) {
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
                            + "Either add them to wireColumns() / readEntity() or add a skip-set "
                            + "entry with rationale.",
                    mapper.getClass().getSimpleName(), tableName, missing));
        }
    }

    private static Set<String> perTableSkip(String tableName) {
        return PER_TABLE_COLUMNS_POPULATED_OUTSIDE_THE_MAPPER.getOrDefault(tableName, Set.of());
    }

    private static String destinationTableName(EntityType entity) {
        String override = DESTINATION_TABLE_OVERRIDE.get(entity);
        return override != null ? override : "t_" + entity.temporaryTableSuffix();
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
        ensureSchemaMigratedSinceNoSpringContextDrivesFlywayHere(
                pg.jdbcUrl(), pg.username(), pg.password());
        return DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
    }

    private static void ensureSchemaMigratedSinceNoSpringContextDrivesFlywayHere(
            String jdbcUrl, String user, String password) {
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

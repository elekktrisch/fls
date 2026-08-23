package ch.alpenflight.legacyextract;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

@EnabledIf(value = "dockerAvailable",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class LegacyProducerSelectCompatibilityLevelTest {

    private static final MssqlTestContainerLifecycle MSSQL = MssqlTestContainerLifecycle.shared();
    private static final boolean DOCKER_AVAILABLE = tryStartContainer();

    private static final String RESIDUAL_LIMIT =
            "Residual limit: this guard proves each producer SELECT COMPILES and STREAMS against the "
                    + "canonical FLSTest schema at the compatibility level the legacy scripts declare. "
                    + "It does not prove row fidelity, and it scores only the tables the FLSTest "
                    + "fixture creates — a binding over a table the fixture lacks reads zero rows here "
                    + "and still needs the fan-out.";

    private static final String GUID_RECORD_ID = "33333333-3333-3333-3333-333333333333";
    private static final String BRACED_GUID_RECORD_ID = "{44444444-4444-4444-4444-444444444444}";
    private static final String GUID_INSIDE_THE_BRACED_RECORD_ID =
            "44444444-4444-4444-4444-444444444444";
    private static final String NON_GUID_RECORD_ID = "not-a-guid";
    private static final String ACTOR_USERNAME = "t41.compat.actor";
    private static final String ORPHAN_USERNAME = "t41.compat.orphan";

    private static boolean tryStartContainer() {
        try {
            MSSQL.start();
            return true;
        } catch (Throwable dockerUnreachable) {
            System.err.println(
                    "[alpenflight-extract] Skipping LegacyProducerSelectCompatibilityLevelTest — "
                            + "Docker unreachable. Root cause: " + dockerUnreachable.getMessage());
            return false;
        }
    }

    static boolean dockerAvailable() {
        return DOCKER_AVAILABLE;
    }

    @BeforeAll
    static void seedFlsTestFixture() throws IOException {
        MSSQL.seedLegacyFixtureOnce(LegacyExtractFixtureSeeder.locateFlsTestFixtureRoot());
    }

    @Test
    void theFixtureRunsAtTheCompatibilityLevelTheLegacySeedDeclares() throws Exception {
        int levelDeclaredByTheLegacyScripts = 100;
        try (Connection connection = openLegacyConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT compatibility_level FROM sys.databases WHERE database_id = DB_ID()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1))
                    .as("`2 Alter Database.sql:3` sets COMPATIBILITY_LEVEL = %d and `e2e/scripts/seed.sh` "
                            + "applies it, so this fixture must score T-SQL at the same level. A fixture "
                            + "left at the container default accepts syntax the production export "
                            + "rejects. %s", levelDeclaredByTheLegacyScripts, RESIDUAL_LIMIT)
                    .isEqualTo(levelDeclaredByTheLegacyScripts);
        }
    }

    @Test
    void everyRegisteredProducerSelectCompilesAndStreamsAtTheLegacyCompatibilityLevel() throws Exception {
        Map<EntityType, String> failuresByEntity = new LinkedHashMap<>();
        try (Connection connection = openLegacyConnection()) {
            for (EntityType entity : EntityType.values()) {
                if (!MapperLegacyBindings.isRegistered(entity)) {
                    continue;
                }
                String select = MapperLegacyBindings.selectForProducer(entity);
                if (select.isBlank()) {
                    continue;
                }
                try (Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery(select)) {
                    while (rs.next()) {
                        rs.getObject(1);
                    }
                } catch (SQLException producerSelectRejectedByTheLegacyDatabase) {
                    failuresByEntity.put(entity,
                            producerSelectRejectedByTheLegacyDatabase.getMessage());
                }
            }
        }
        assertThat(failuresByEntity)
                .as("Every registered producer SELECT must open its cursor against the legacy "
                        + "database. `alpenflight-export` streams these statements one by one and "
                        + "aborts on the first rejection, so one construct the compatibility level "
                        + "refuses reads zero rows and reds the fan-out. %s", RESIDUAL_LIMIT)
                .isEmpty();
    }

    @Test
    void theAuditLogProducerSelectStreamsRowsAndSplitsGuidFromNonGuidRecordIds() throws Exception {
        try (Connection connection = openLegacyConnection()) {
            seedAuditRows(connection);
            try {
                List<Map<String, Object>> rows = readSeededAuditProducerRows(connection);

                assertThat(rows)
                        .as("The AUDIT_LOG producer SELECT must return the three seeded rows. A "
                                + "statement the legacy compatibility level refuses reads zero rows "
                                + "and the ingest never runs. %s", RESIDUAL_LIMIT)
                        .hasSize(3);

                assertThat(valuesOf(rows, "ResolvedTargetEntityId"))
                        .as("a canonical and a braced RecordId convert to a uniqueidentifier; a "
                                + "non-GUID RecordId stays NULL")
                        .containsExactlyInAnyOrder(
                                upper(GUID_RECORD_ID), upper(GUID_INSIDE_THE_BRACED_RECORD_ID), null);
                assertThat(valuesOf(rows, "ResolvedLegacyTargetRecordId"))
                        .as("only the non-GUID RecordId falls through to the legacy text column")
                        .containsExactlyInAnyOrder(NON_GUID_RECORD_ID, null, null);
                assertThat(valuesOf(rows, "ResolvedActorUserId"))
                        .as("the LEFT JOIN over Users runs and matches no live user, because the "
                                + "FLSTest fixture seeds schema only")
                        .containsExactlyInAnyOrder(null, null, null);
                assertThat(valuesOf(rows, "ResolvedLegacyOrphanActorId"))
                        .as("HASHBYTES('SHA2_256') derives a stable orphan actor id for every "
                                + "unresolved UserName, and it runs at the legacy compatibility "
                                + "level")
                        .doesNotContainNull();
            } finally {
                deleteAuditRows(connection);
            }
        }
    }

    private static List<String> valuesOf(List<Map<String, Object>> rows, String column) {
        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            values.add(value == null ? null : value.toString());
        }
        return values;
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private static List<Map<String, Object>> readSeededAuditProducerRows(Connection connection)
            throws SQLException {
        String select = MapperLegacyBindings.selectForProducer(EntityType.AUDIT_LOG);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(select)) {
            while (rs.next()) {
                String userName = rs.getString("UserName");
                if (!ACTOR_USERNAME.equals(userName) && !ORPHAN_USERNAME.equals(userName)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ResolvedTargetEntityId", rs.getObject("ResolvedTargetEntityId"));
                row.put("ResolvedLegacyTargetRecordId", rs.getObject("ResolvedLegacyTargetRecordId"));
                row.put("ResolvedActorUserId", rs.getObject("ResolvedActorUserId"));
                row.put("ResolvedLegacyOrphanActorId", rs.getObject("ResolvedLegacyOrphanActorId"));
                rows.add(row);
            }
        }
        return rows;
    }

    private static void seedAuditRows(Connection connection) throws SQLException {
        deleteAuditRows(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO AuditLogs (UserName, EventDateUTC, EventType, TypeFullName, RecordId)
                    VALUES (N'%s', SYSUTCDATETIME(), 0, N'FLS.Server.Data.DbEntities.Flight', N'%s'),
                           (N'%s', SYSUTCDATETIME(), 2, N'FLS.Server.Data.DbEntities.Aircraft', N'%s'),
                           (N'%s', SYSUTCDATETIME(), 1, N'FLS.Server.Data.DbEntities.Article', N'%s')
                    """.formatted(ACTOR_USERNAME, GUID_RECORD_ID,
                            ORPHAN_USERNAME, BRACED_GUID_RECORD_ID,
                            ACTOR_USERNAME, NON_GUID_RECORD_ID));
        }
    }

    private static void deleteAuditRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM AuditLogs WHERE UserName IN (N'" + ACTOR_USERNAME
                    + "', N'" + ORPHAN_USERNAME + "')");
        }
    }

    private static Connection openLegacyConnection() throws SQLException {
        return MSSQL.dataSource().getConnection();
    }
}

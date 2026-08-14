package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EntityStreamIngestorFanOutKeyingTest {

    private final EntityStreamIngestor ingestor = new EntityStreamIngestor(KnownMappers.all());

    @Test
    void fanout_entity_insert_carries_both_id_and_legacy_guid_unaliased() {
        assertThat(EntityType.LOCATION.fansOut()).isTrue();
        String insert = ingestor.insertStatementFor(EntityType.LOCATION);
        String columnList = insertColumnList(insert);

        assertThat(columnList).startsWith("id, legacy_guid, club_id");
        List<String> columns = List.of(columnList.split(",\\s*"));
        assertThat(columns).contains("legacy_guid");
        assertThat(columns.stream().filter("id"::equals).count()).isEqualTo(1L);
    }

    @Test
    void non_fanout_entity_insert_aliases_legacy_guid_to_id() {
        assertThat(EntityType.COUNTRY.fansOut()).isFalse();
        String insert = ingestor.insertStatementFor(EntityType.COUNTRY);
        String columnList = insertColumnList(insert);

        assertThat(columnList).startsWith("id");
        assertThat(columnList).doesNotContain("legacy_guid");
    }

    @Test
    void temp_table_ddl_is_composite_for_fanout_and_two_column_for_non_fanout()
            throws SQLException {
        List<String> ddl = captureCreateTemporaryIdMapTablesDdl();

        String locationTable = LegacyIdMapTables.temporaryTableName(EntityType.LOCATION);
        String iopTable = LegacyIdMapTables.temporaryTableName(EntityType.INOUTBOUND_POINT);
        String countryTable = LegacyIdMapTables.temporaryTableName(EntityType.COUNTRY);
        String clubTable = LegacyIdMapTables.temporaryTableName(EntityType.CLUB);

        assertThat(ddlFor(ddl, locationTable))
                .contains("legacy_guid uuid")
                .contains("club_id uuid")
                .contains("new_uuid uuid NOT NULL")
                .contains("PRIMARY KEY (legacy_guid, club_id)");
        assertThat(ddlFor(ddl, iopTable))
                .contains("PRIMARY KEY (legacy_guid, club_id)");

        assertThat(ddlFor(ddl, countryTable))
                .contains("legacy_guid uuid PRIMARY KEY")
                .doesNotContain("club_id");
        assertThat(ddlFor(ddl, clubTable))
                .contains("legacy_guid uuid PRIMARY KEY")
                .doesNotContain("club_id");
    }

    private List<String> captureCreateTemporaryIdMapTablesDdl() throws SQLException {
        List<String> executed = new ArrayList<>();
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.execute(Mockito.anyString())).thenAnswer(invocation -> {
            executed.add(invocation.getArgument(0));
            return false;
        });
        ingestor.createTemporaryIdMapTables(connection);
        return executed;
    }

    private static String ddlFor(List<String> ddl, String tableName) {
        return ddl.stream()
                .filter(sql -> sql.contains(" " + tableName + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No CREATE TEMP TABLE for " + tableName));
    }

    private static String insertColumnList(String insert) {
        int open = insert.indexOf('(');
        int close = insert.indexOf(") VALUES");
        return insert.substring(open + 1, close).trim();
    }
}

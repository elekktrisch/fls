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

/**
 * J-0b T-06 — fan-out keying on the ingest side. Two seams under test, both
 * gated on {@link EntityType#fansOut()}:
 *
 * <ul>
 *   <li>{@code destinationColumnNames} de-aliasing: a fan-out entity carries
 *       BOTH {@code id} (the derived replica id) and {@code legacy_guid} (the
 *       shared key) as separate real destination columns — no aliasing — while
 *       a non-fan-out entity keeps the {@code legacy_guid → id} alias so its
 *       single {@code id} destination column is emitted.</li>
 *   <li>{@code createTemporaryIdMapTables} DDL: a fan-out entity gets a
 *       composite {@code (legacy_guid, club_id)} PK temp table (3 columns); a
 *       non-fan-out entity keeps the 2-column single-key shape.</li>
 * </ul>
 */
class EntityStreamIngestorFanOutKeyingTest {

    private final EntityStreamIngestor ingestor = new EntityStreamIngestor(KnownMappers.all());

    @Test
    void fanout_entity_insert_carries_both_id_and_legacy_guid_unaliased() {
        // LOCATION is a fan-out entity: its mapper columns() lead with the
        // derived id then the shared legacy_guid, and the INSERT must carry
        // both verbatim (no legacy_guid → id alias that would PK-collide on
        // the 2nd replica and drop the shared-key column).
        assertThat(EntityType.LOCATION.fansOut()).isTrue();
        String insert = ingestor.insertStatementFor(EntityType.LOCATION);
        String columnList = insertColumnList(insert);

        assertThat(columnList).startsWith("id, legacy_guid, club_id");
        // Both are present and distinct — the alias would have produced a
        // duplicate "id" column and dropped "legacy_guid".
        List<String> columns = List.of(columnList.split(",\\s*"));
        assertThat(columns).contains("legacy_guid");
        assertThat(columns.stream().filter("id"::equals).count()).isEqualTo(1L);
    }

    @Test
    void non_fanout_entity_insert_aliases_legacy_guid_to_id() {
        // COUNTRY is NOT fan-out: its mapper emits legacy_guid as the carrier
        // for the destination id, so destinationColumnNames keeps the alias and
        // the INSERT's first column is the single "id" (never "legacy_guid").
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

        // Non-fan-out entities keep the 2-column single-key shape untouched
        // (CLUB/SYSTEM_GLOBAL/identity still rely on it — S-183…S-189).
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

    /** Extract the parenthesised column list of an {@code INSERT INTO t (...) VALUES}. */
    private static String insertColumnList(String insert) {
        int open = insert.indexOf('(');
        int close = insert.indexOf(") VALUES");
        return insert.substring(open + 1, close).trim();
    }
}

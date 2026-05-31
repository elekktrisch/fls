package ch.alpenflight.migration.tool;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ExportCommandSmokeTest {

    @Test
    void helpExitsZero() {
        int code = new CommandLine(new ExportCommand()).execute("--help");
        assertThat(code).isZero();
    }

    @Test
    void versionExitsZero() {
        int code = new CommandLine(new ExportCommand()).execute("--version");
        assertThat(code).isZero();
    }

    @Test
    void registeredEntitiesAreTheFiveSliceBindings() {
        List<EntityType> entities = ExportCommand.registeredEntities();
        assertThat(entities).containsExactlyInAnyOrder(
                EntityType.COUNTRY, EntityType.LANGUAGE, EntityType.CLUB_STATE,
                EntityType.CLUB, EntityType.USER);
    }

    @Test
    void forcesReadOnlyIntentWhenAbsent() {
        String hardened = LegacyJdbcReader.forceReadOnlyIntent(
                "jdbc:sqlserver://host:1433;databaseName=FLS");
        assertThat(hardened).contains("ApplicationIntent=ReadOnly");
    }

    @Test
    void overridesReadWriteIntent() {
        String hardened = LegacyJdbcReader.forceReadOnlyIntent(
                "jdbc:sqlserver://host;ApplicationIntent=ReadWrite;databaseName=FLS");
        assertThat(hardened)
                .contains("ApplicationIntent=ReadOnly")
                .doesNotContain("ReadWrite");
    }
}

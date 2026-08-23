package ch.alpenflight.migration.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void registeredEntitiesMatchTheBoundLegacyEntities() {
        List<EntityType> entities = ExportCommand.registeredEntities();
        assertThat(entities).containsExactlyInAnyOrder(
                EntityType.COUNTRY, EntityType.LANGUAGE, EntityType.CLUB_STATE,
                EntityType.CLUB, EntityType.PERSON, EntityType.USER,
                EntityType.PERSON_CLUB,
                EntityType.LOCATION, EntityType.INOUTBOUND_POINT,
                EntityType.AIRCRAFT, EntityType.AIRCRAFT_AIRCRAFT_STATE,
                EntityType.AIRCRAFT_OPERATING_COUNTER,
                EntityType.FLIGHT, EntityType.FLIGHT_CREW,
                EntityType.FLIGHT_TYPE, EntityType.START_TYPE,
                EntityType.AIRCRAFT_RESERVATION, EntityType.AIRCRAFT_RESERVATION_TYPE,
                EntityType.PLANNING_DAY, EntityType.PLANNING_DAY_ASSIGNMENT,
                EntityType.PLANNING_DAY_ASSIGNMENT_TYPE,
                EntityType.ACCOUNTING_RULE_FILTER,
                EntityType.ARTICLE,
                EntityType.DELIVERY, EntityType.DELIVERY_ITEM,
                EntityType.PERSON_FLIGHT_TIME_CREDIT,
                EntityType.PERSON_FLIGHT_TIME_CREDIT_TRANSACTION,
                EntityType.AUDIT_LOG);
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

    @Test
    void rejectsDuplicateApplicationIntent() {
        assertThatThrownBy(() -> LegacyJdbcReader.forceReadOnlyIntent(
                "jdbc:sqlserver://host;ApplicationIntent=ReadOnly;"
                        + "databaseName=FLS;applicationintent=ReadWrite"))
                .isInstanceOf(ExportException.class)
                .satisfies(e -> assertThat(((ExportException) e).exitCode())
                        .isEqualTo(ExitCode.USAGE));
    }
}

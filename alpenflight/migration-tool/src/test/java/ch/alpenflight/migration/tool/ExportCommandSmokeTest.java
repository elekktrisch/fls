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
        // The export tool exports exactly the entities with a legacy producer
        // binding (MapperLegacyBindings): the original 5 IDENTITY slice +
        // LOCATION / INOUTBOUND_POINT added in J-0 T-02c (the Location export half)
        // + PERSON wired in J-0c T-21 (so USER.person_id resolves at ingest)
        // + the AIRCRAFT aggregate (AIRCRAFT + its two aggregate-internal children)
        // registered in J-1 T-04
        // + the FLIGHT group (FLIGHT + the aggregate-internal FLIGHT_CREW, plus the
        // FLIGHT_TYPE and START_TYPE references it resolves against) bound in J-2 T-07.
        List<EntityType> entities = ExportCommand.registeredEntities();
        assertThat(entities).containsExactlyInAnyOrder(
                EntityType.COUNTRY, EntityType.LANGUAGE, EntityType.CLUB_STATE,
                EntityType.CLUB, EntityType.PERSON, EntityType.USER,
                EntityType.LOCATION, EntityType.INOUTBOUND_POINT,
                EntityType.AIRCRAFT, EntityType.AIRCRAFT_AIRCRAFT_STATE,
                EntityType.AIRCRAFT_OPERATING_COUNTER,
                EntityType.FLIGHT, EntityType.FLIGHT_CREW,
                EntityType.FLIGHT_TYPE, EntityType.START_TYPE);
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
        // The driver honours the LAST occurrence, so rewriting only the first
        // could let a trailing ReadWrite slip past — reject outright.
        assertThatThrownBy(() -> LegacyJdbcReader.forceReadOnlyIntent(
                "jdbc:sqlserver://host;ApplicationIntent=ReadOnly;"
                        + "databaseName=FLS;applicationintent=ReadWrite"))
                .isInstanceOf(ExportException.class)
                .satisfies(e -> assertThat(((ExportException) e).exitCode())
                        .isEqualTo(ExitCode.USAGE));
    }
}

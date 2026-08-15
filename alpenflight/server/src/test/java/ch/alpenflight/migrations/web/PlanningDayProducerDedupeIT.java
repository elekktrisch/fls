package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("slow")
class PlanningDayProducerDedupeIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private final UUID clubId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();
    private final LocalDate day = LocalDate.of(2026, 7, 4);

    private final UUID dupKeepFirstId = UUID.randomUUID();
    private final UUID dupLaterId = UUID.randomUUID();
    private final UUID distinctLocationId = UUID.randomUUID();
    private final UUID distinctRowId = UUID.randomUUID();

    @BeforeEach
    void seedLegacyShapedStagingTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS PlanningDays (
                    PlanningDayId    UUID PRIMARY KEY,
                    ClubId           UUID NOT NULL,
                    Day              DATE NOT NULL,
                    LocationId       UUID NOT NULL,
                    Remarks          TEXT,
                    CreatedOn        TIMESTAMP NOT NULL,
                    CreatedByUserId  UUID,
                    ModifiedOn       TIMESTAMP,
                    ModifiedByUserId UUID,
                    DeletedOn        TIMESTAMP,
                    DeletedByUserId  UUID
                )
                """);
        jdbc.update("DELETE FROM PlanningDays");

        insertRow(dupKeepFirstId, locationId, Timestamp.valueOf("2020-01-01 08:00:00"), "first");
        insertRow(dupLaterId, locationId, Timestamp.valueOf("2021-06-15 09:30:00"), "later dup");
        insertRow(distinctRowId, distinctLocationId,
                Timestamp.valueOf("2020-01-01 08:00:00"), "distinct location");
    }

    @AfterEach
    void dropStagingTable() {
        jdbc.execute("DROP TABLE IF EXISTS PlanningDayAssignments");
        jdbc.execute("DROP TABLE IF EXISTS PlanningDays");
    }

    @Test
    void producerSelectKeepsExactlyOneRowPerClubDayLocationOrderedByCreatedOnThenId() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.PLANNING_DAY);

        List<Map<String, Object>> rows = jdbc.queryForList(select);

        List<UUID> survivingIds = rows.stream()
                .map(r -> UUID.fromString(r.get("planningdayid").toString()))
                .sorted()
                .toList();

        assertThat(survivingIds)
                .as("the producer SELECT keep-firsts duplicate (ClubId, Day, LocationId) "
                        + "rows: the earlier-CreatedOn dup survives, the later dup is "
                        + "dropped, and the distinct-location row is untouched — else "
                        + "the duplicate would 23505 on ux_pln_club_date_loc at ingest")
                .containsExactlyInAnyOrder(dupKeepFirstId, distinctRowId)
                .doesNotContain(dupLaterId);

        long survivorsForDupKey = rows.stream()
                .filter(r -> r.get("locationid").toString().equals(locationId.toString()))
                .count();
        assertThat(survivorsForDupKey)
                .as("exactly one survivor for the duplicated (ClubId, Day, LocationId) key")
                .isEqualTo(1L);
    }

    @Test
    void assignmentSelectRemapsAssignmentsOfDroppedDaysOntoTheKeptFirstSurvivor() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS PlanningDayAssignments (
                    PlanningDayAssignmentId UUID PRIMARY KEY,
                    AssignedPlanningDayId   UUID NOT NULL,
                    AssignedPersonId        UUID,
                    AssignmentTypeId        UUID,
                    Remarks                 TEXT,
                    CreatedOn               TIMESTAMP NOT NULL,
                    CreatedByUserId         UUID,
                    ModifiedOn              TIMESTAMP,
                    ModifiedByUserId        UUID,
                    DeletedOn               TIMESTAMP,
                    DeletedByUserId         UUID
                )
                """);
        jdbc.update("DELETE FROM PlanningDayAssignments");
        UUID onSurvivor = UUID.randomUUID();
        UUID onDroppedDup = UUID.randomUUID();
        insertAssignment(onSurvivor, dupKeepFirstId);
        insertAssignment(onDroppedDup, dupLaterId);

        String select = MapperLegacyBindings.selectForProducer(EntityType.PLANNING_DAY_ASSIGNMENT);
        List<Map<String, Object>> rows = jdbc.queryForList(select);

        assertThat(rows)
                .as("both assignments are exported (none dropped) — the dropped-day "
                        + "assignment is REMAPPED, not discarded")
                .hasSize(2);
        for (Map<String, Object> row : rows) {
            UUID assignmentId = UUID.fromString(row.get("planningdayassignmentid").toString());
            UUID parentDay = UUID.fromString(row.get("assignedplanningdayid").toString());
            assertThat(parentDay)
                    .as("assignment %s must point at the kept-first survivor day "
                            + "(not the dropped dup %s) — else fk_pda_planning_day_id "
                            + "23503s at ingest", assignmentId, dupLaterId)
                    .isEqualTo(dupKeepFirstId);
        }
    }

    @Test
    void assignmentSelectDedupesPostRemapCompositeKeepingTheLiveEarliestRow() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS PlanningDayAssignments (
                    PlanningDayAssignmentId UUID PRIMARY KEY,
                    AssignedPlanningDayId   UUID NOT NULL,
                    AssignedPersonId        UUID,
                    AssignmentTypeId        UUID,
                    Remarks                 TEXT,
                    CreatedOn               TIMESTAMP NOT NULL,
                    CreatedByUserId         UUID,
                    ModifiedOn              TIMESTAMP,
                    ModifiedByUserId        UUID,
                    DeletedOn               TIMESTAMP,
                    DeletedByUserId         UUID
                )
                """);
        jdbc.update("DELETE FROM PlanningDayAssignments");

        UUID sharedPerson = UUID.randomUUID();
        UUID sharedType = UUID.randomUUID();

        UUID liveWinner = UUID.randomUUID();
        UUID softDeletedLoser = UUID.randomUUID();
        insertAssignment(liveWinner, dupKeepFirstId, sharedPerson, sharedType,
                Timestamp.valueOf("2020-01-01 08:00:00"), null);
        insertAssignment(softDeletedLoser, dupLaterId, sharedPerson, sharedType,
                Timestamp.valueOf("2021-06-15 09:30:00"),
                Timestamp.valueOf("2022-01-01 00:00:00"));

        UUID distinctComposite = UUID.randomUUID();
        insertAssignment(distinctComposite, dupLaterId, UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.valueOf("2020-01-01 08:00:00"), null);

        String select = MapperLegacyBindings.selectForProducer(EntityType.PLANNING_DAY_ASSIGNMENT);
        List<Map<String, Object>> rows = jdbc.queryForList(select);

        List<UUID> survivingIds = rows.stream()
                .map(r -> UUID.fromString(r.get("planningdayassignmentid").toString()))
                .sorted()
                .toList();

        assertThat(survivingIds)
                .as("the post-remap composite dedupe keeps the LIVE earliest assignment "
                        + "for the colliding (person, type) and drops the soft-deleted later "
                        + "dup — else two identical (planning_day_id, person, type) rows "
                        + "23505 on ux_pda_composite at ingest; the distinct-composite "
                        + "assignment is untouched")
                .containsExactlyInAnyOrder(liveWinner, distinctComposite)
                .doesNotContain(softDeletedLoser);

        long collidingComposite = rows.stream()
                .filter(r -> r.get("assignedpersonid").toString().equals(sharedPerson.toString())
                        && r.get("assignmenttypeid").toString().equals(sharedType.toString()))
                .count();
        assertThat(collidingComposite)
                .as("exactly one survivor for the colliding (planning_day_id, person, type)")
                .isEqualTo(1L);
        for (Map<String, Object> row : rows) {
            assertThat(UUID.fromString(row.get("assignedplanningdayid").toString()))
                    .isEqualTo(dupKeepFirstId);
        }
    }

    private void insertAssignment(UUID id, UUID planningDayId) {
        insertAssignment(id, planningDayId, UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.valueOf("2020-01-01 08:00:00"), null);
    }

    private void insertAssignment(UUID id, UUID planningDayId, UUID personId,
                                  UUID assignmentTypeId, Timestamp createdOn,
                                  Timestamp deletedOn) {
        jdbc.update("""
                INSERT INTO PlanningDayAssignments
                    (PlanningDayAssignmentId, AssignedPlanningDayId, AssignedPersonId,
                     AssignmentTypeId, Remarks, CreatedOn, CreatedByUserId,
                     ModifiedOn, ModifiedByUserId, DeletedOn, DeletedByUserId)
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, NULL)
                """,
                id, planningDayId, personId, assignmentTypeId, "crew",
                createdOn, deletedOn);
    }

    private void insertRow(UUID id, UUID location, Timestamp createdOn, String remarks) {
        jdbc.update("""
                INSERT INTO PlanningDays
                    (PlanningDayId, ClubId, Day, LocationId, Remarks,
                     CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                     DeletedOn, DeletedByUserId)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, NULL, NULL)
                """,
                id, clubId, java.sql.Date.valueOf(day), location, remarks,
                createdOn, createdOn);
    }
}

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

/**
 * J-6 T-11b — the PlanningDay producer-side dedupe-keep-first proof.
 *
 * <p>The real legacy {@code PlanningDays} table carries DUPLICATE
 * {@code (ClubId, Day, LocationId)} rows — it has no legacy UNIQUE constraint.
 * V4's {@code ux_pln_club_date_loc} partial unique
 * ({@code (operating_club_id, planning_date, location_id) WHERE deleted_on IS NULL})
 * forbids them. {@code PLANNING_DAY} is NOT fan-out, so two dup legacy rows
 * resolve to the SAME provisioned club + the SAME own-club Location replica —
 * the 2nd INSERT then violates {@code ux_pln_club_date_loc} and the ingest
 * fail-closes with {@code sqlstate 23505} ({@code INGEST_INTERNAL_ERROR}). That
 * was the §4 fanout gate blocker.
 *
 * <p>The fix is the keep-first the {@code PlanningDayMapper} Javadoc promises:
 * the {@code PLANNING_DAY} producer SELECT keeps one deterministically-first row
 * per {@code (ClubId, Day, LocationId)} via {@code ROW_NUMBER() OVER (PARTITION
 * BY … ORDER BY CreatedOn, PlanningDayId) … WHERE rn = 1}, so the duplicate
 * never reaches the mapper / the bundle / the ingest.
 *
 * <p>This IT runs the REAL bound producer SELECT
 * ({@link MapperLegacyBindings#selectForProducer}) against a legacy-shaped
 * {@code PlanningDays} staging table seeded with TWO dups + one distinct row,
 * and asserts EXACTLY the keep-first survivors come back — locking the dedupe so
 * a regression that drops it (and reintroduces the 23505) is caught at build,
 * not at the ~20-min fanout. The SELECT's {@code ROW_NUMBER() OVER} window is
 * dialect-portable (the live legacy MSSQL T-SQL and this Postgres container
 * evaluate it identically); the round-trip ingest of the deduped output is
 * proven by {@link PlanningDayMigrationRoundTripIT}.
 *
 * <p><strong>J-6 T-16 — the 23503 follow-on.</strong> Once the dedupe cleared the
 * 23505, the §4 fanout surfaced a DEEPER masked error: {@code sqlstate 23503}
 * (FK violation). The real legacy FLSTest fixture has TWO planning days
 * ('Test'/'Test2') on the SAME {@code (Club, GETDATE()+1, LSZK)} — one is dropped
 * by the dedupe — yet BOTH carry assignments. An assignment of the DROPPED day
 * would INSERT a {@code planning_day_id} with no parent row →
 * {@code fk_pda_planning_day_id} violation. The {@code PLANNING_DAY_ASSIGNMENT}
 * producer SELECT therefore REMAPS each assignment's {@code planning_day_id} onto
 * the SURVIVING (kept-first) day for its parent's {@code (ClubId, Day, LocationId)}.
 * The second test here seeds an assignment on the dropped dup and asserts the
 * producer SELECT projects the SURVIVOR's id — locking the remap behaviourally so
 * a regression can't silently reintroduce the 23503.
 *
 * <p><strong>J-7 T-17 — the 23505 follow-on.</strong> The 23503 remap fix
 * introduced a NEW collision: when the two duplicate days' assignments share the
 * SAME {@code (AssignedPersonId, AssignmentTypeId)}, the remap collapses BOTH onto
 * the surviving day → two rows with an identical {@code (planning_day_id,
 * assigned_person_id, assignment_type_id)} → V4's {@code ux_pda_composite} UNIQUE
 * 23505 (the §4 fanout blocker the backend.log dig diagnosed). The producer SELECT
 * therefore dedupe-keep-firsts on the POST-REMAP composite, preferring a LIVE
 * (non-{@code DeletedOn}) row over a soft-deleted one then earliest CreatedOn. The
 * third test seeds that exact collision and asserts exactly one survivor (the live
 * earliest) for the composite — locking the dedupe so a regression can't silently
 * reintroduce the 23505.
 */
@Tag("slow")
class PlanningDayProducerDedupeIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private final UUID clubId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();
    private final LocalDate day = LocalDate.of(2026, 7, 4);

    // The keep-first survivor of the duplicate pair: oldest CreatedOn wins.
    private final UUID dupKeepFirstId = UUID.randomUUID();
    private final UUID dupLaterId = UUID.randomUUID();
    // A row sharing the club+day but a DIFFERENT location — a distinct key, NOT
    // a dup, so it must also survive (the dedupe partitions on all three columns).
    private final UUID distinctLocationId = UUID.randomUUID();
    private final UUID distinctRowId = UUID.randomUUID();

    @BeforeEach
    void seedLegacyShapedStagingTable() {
        // A legacy-shaped staging table standing in for the MSSQL PlanningDays
        // the producer SELECT reads. Unquoted mixed-case identifiers fold to
        // lowercase in Postgres, matching the unquoted names in the bound SELECT.
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

        // Two duplicate (ClubId, Day, LocationId) rows. The keep-first survivor
        // is the one with the earlier CreatedOn (ORDER BY CreatedOn, PlanningDayId).
        insertRow(dupKeepFirstId, locationId, Timestamp.valueOf("2020-01-01 08:00:00"), "first");
        insertRow(dupLaterId, locationId, Timestamp.valueOf("2021-06-15 09:30:00"), "later dup");
        // A distinct key (same club+day, different location) — must NOT be dropped.
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

        // The later duplicate is dropped; the keep-first survivor + the distinct
        // row remain. Exactly one row survives per (ClubId, Day, LocationId).
        assertThat(survivingIds)
                .as("the producer SELECT keep-firsts duplicate (ClubId, Day, LocationId) "
                        + "rows: the earlier-CreatedOn dup survives, the later dup is "
                        + "dropped, and the distinct-location row is untouched — else "
                        + "the duplicate would 23505 on ux_pln_club_date_loc at ingest")
                .containsExactlyInAnyOrder(dupKeepFirstId, distinctRowId)
                .doesNotContain(dupLaterId);

        // The dup pair collapses to exactly one survivor for that key.
        long survivorsForDupKey = rows.stream()
                .filter(r -> r.get("locationid").toString().equals(locationId.toString()))
                .count();
        assertThat(survivorsForDupKey)
                .as("exactly one survivor for the duplicated (ClubId, Day, LocationId) key")
                .isEqualTo(1L);
    }

    @Test
    void assignmentSelectRemapsAssignmentsOfDroppedDaysOntoTheKeptFirstSurvivor() {
        // A legacy-shaped PlanningDayAssignments staging table (no own ClubId — the
        // real DBUpdate_v1.0.1 DDL; operating_club_id is JOIN-denormalised). Seed
        // TWO assignments: one on the kept-first survivor, one on the DROPPED later
        // dup. The producer SELECT must project BOTH with planning_day_id ==
        // the SURVIVOR's id (the dropped day's assignment is remapped) — so neither
        // FK-violates fk_pda_planning_day_id at ingest.
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
        // J-7 T-17 — the ux_pda_composite 23505 case. The real legacy FLSTest
        // fixture has TWO duplicate planning days ('Test'/'Test2') on the SAME
        // (Club, Day, LSZK), BOTH carrying an assignment with the SAME
        // (AssignedPersonId, AssignmentTypeId). The J-6 remap re-points BOTH at
        // the one surviving day → two rows with an identical
        // (planning_day_id, assigned_person_id, assignment_type_id) → V4's
        // ux_pda_composite UNIQUE 23505 at the 2nd INSERT. The producer SELECT
        // must dedupe-keep-first on the POST-REMAP composite so exactly ONE row
        // for that composite survives — and the LIVE / earliest-CreatedOn row
        // wins over a soft-deleted / later one.
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

        // ONE (person, type) shared by both duplicate days' assignments.
        UUID sharedPerson = UUID.randomUUID();
        UUID sharedType = UUID.randomUUID();

        // On the kept-first survivor day: the LIVE, earlier assignment — the
        // keep-first winner for the post-remap composite.
        UUID liveWinner = UUID.randomUUID();
        // On the DROPPED dup day: a soft-deleted (DeletedOn set), later
        // assignment with the SAME (person, type) — must be dropped (the live
        // earlier row wins; a soft-deleted survivor would silently drop a real
        // crew assignment, the gap-hunter "soft-delete-blind" rider).
        UUID softDeletedLoser = UUID.randomUUID();
        insertAssignment(liveWinner, dupKeepFirstId, sharedPerson, sharedType,
                Timestamp.valueOf("2020-01-01 08:00:00"), null);
        insertAssignment(softDeletedLoser, dupLaterId, sharedPerson, sharedType,
                Timestamp.valueOf("2021-06-15 09:30:00"),
                Timestamp.valueOf("2022-01-01 00:00:00"));

        // A second, DISTINCT (person, type) on the dropped dup day: remaps onto
        // the survivor too but does NOT collide on the composite — must survive.
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

        // Exactly one row per post-remap composite on the survivor day.
        long collidingComposite = rows.stream()
                .filter(r -> r.get("assignedpersonid").toString().equals(sharedPerson.toString())
                        && r.get("assignmenttypeid").toString().equals(sharedType.toString()))
                .count();
        assertThat(collidingComposite)
                .as("exactly one survivor for the colliding (planning_day_id, person, type)")
                .isEqualTo(1L);
        // Every surviving row points at the kept-first survivor day.
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

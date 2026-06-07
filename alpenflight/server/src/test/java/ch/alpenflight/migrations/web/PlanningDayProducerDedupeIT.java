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

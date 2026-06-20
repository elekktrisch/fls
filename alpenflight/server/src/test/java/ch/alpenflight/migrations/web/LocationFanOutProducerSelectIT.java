package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * J-27 T-03 — the Location club-B fan-out producer-SELECT proof.
 *
 * <p>One shared legacy {@code Locations} row referenced by TWO clubs (each via
 * {@code Clubs.HomebaseId}, the exact precondition the J-0c legacy spec builds)
 * must fan out to one bundle row PER referencing club. The fanout RED is
 * {@code fan-out-migration-parity.spec.ts:167}: club-B's admin cannot find its
 * OWN fanned-out copy of the migrated Location by name. club-A passes — so
 * club-A's row migrates but club-B's distinct fanned-out row does not.
 *
 * <p>Only the REAL export validates the producer SELECT — the synth bundle
 * aliases columns and never runs it ({@code project_synth_bundle_doesnt_validate
 * _producer_select}), so this seam was previously unguarded in {@code check}.
 * This IT runs the REAL bound LOCATION + INOUTBOUND_POINT producer SELECTs
 * ({@link MapperLegacyBindings#selectForProducer}) against legacy-shaped
 * {@code Locations} / {@code LocationTypes} / {@code Clubs} / {@code Flights} /
 * {@code InOutboundPoints} staging tables seeded with one shared Location +
 * one shared child IOP referenced by two clubs, and asserts BOTH clubs get a
 * distinct fanned-out row under the SAME name — locking the fan-out at build,
 * not the ~20-min fanout.
 *
 * <p>The LOCATION / INOUTBOUND_POINT producer SELECTs are dialect-portable
 * ANSI joins + {@code UNION} (no {@code JSON_VALUE} / {@code ROW_NUMBER OVER}),
 * so the live legacy MSSQL T-SQL and this Postgres container evaluate them
 * identically — no PG-version gate is needed (unlike
 * {@link AccountingRuleFilterProducerDedupeIT}). The round-trip ingest of the
 * fanned-out output is proven by {@link LocationRealProducerRoundTripIT}.
 */
@Tag("slow")
class LocationFanOutProducerSelectIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    // One shared legacy Location referenced by both clubs.
    private final UUID sharedLocationId = UUID.randomUUID();
    private final UUID locationTypeGuid = UUID.randomUUID();
    private final int locationTypeCupId = 2;
    private final UUID countryId = UUID.randomUUID();
    private final UUID createdBy = UUID.randomUUID();
    // The two referencing clubs — both point HomebaseId at the shared Location.
    private final UUID clubA = UUID.randomUUID();
    private final UUID clubB = UUID.randomUUID();
    // A shared child InOutboundPoint at the shared Location — fans out too.
    private final UUID sharedIopId = UUID.randomUUID();

    private static final String SHARED_NAME = "J0C-FANOUT-IT";

    @BeforeEach
    void seedLegacyShapedStagingTables() {
        // Legacy-shaped staging tables standing in for the MSSQL the producer
        // SELECT reads. Unquoted mixed-case identifiers fold to lowercase in
        // Postgres, matching the unquoted names in the bound SELECT.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS LocationTypes (
                    LocationTypeId    UUID PRIMARY KEY,
                    LocationTypeName  TEXT NOT NULL,
                    LocationTypeCupId INTEGER,
                    IsAirfield        BOOLEAN NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS Locations (
                    LocationId            UUID PRIMARY KEY,
                    LocationName          TEXT NOT NULL,
                    LocationShortName     TEXT,
                    CountryId             UUID NOT NULL,
                    LocationTypeId        UUID NOT NULL,
                    IcaoCode              TEXT,
                    Latitude              TEXT,
                    Longitude             TEXT,
                    Elevation             INTEGER,
                    ElevationUnitType     INTEGER,
                    RunwayDirection       TEXT,
                    RunwayLength          INTEGER,
                    RunwayLengthUnitType  INTEGER,
                    AirportFrequency      TEXT,
                    Description           TEXT,
                    SortIndicator         INTEGER,
                    IsInboundRouteRequired  BOOLEAN NOT NULL DEFAULT false,
                    IsOutboundRouteRequired BOOLEAN NOT NULL DEFAULT false,
                    IsFastEntryRecord     BOOLEAN NOT NULL,
                    CreatedOn             TIMESTAMP NOT NULL,
                    CreatedByUserId       UUID NOT NULL,
                    ModifiedOn            TIMESTAMP,
                    ModifiedByUserId      UUID,
                    DeletedOn             TIMESTAMP,
                    DeletedByUserId       UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS Clubs (
                    ClubId     UUID PRIMARY KEY,
                    HomebaseId UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS Flights (
                    FlightId        UUID PRIMARY KEY,
                    OwnerId         UUID,
                    StartLocationId UUID,
                    LdgLocationId   UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS InOutboundPoints (
                    InOutboundPointId   UUID PRIMARY KEY,
                    LocationId          UUID NOT NULL,
                    InOutboundPointName TEXT NOT NULL,
                    IsInboundPoint      BOOLEAN NOT NULL,
                    IsOutboundPoint     BOOLEAN NOT NULL,
                    CreatedOn           TIMESTAMP NOT NULL,
                    CreatedByUserId     UUID NOT NULL,
                    ModifiedOn          TIMESTAMP,
                    ModifiedByUserId    UUID,
                    DeletedOn           TIMESTAMP,
                    DeletedByUserId     UUID
                )
                """);
        clearStaging();

        jdbc.update("INSERT INTO LocationTypes (LocationTypeId, LocationTypeName, "
                + "LocationTypeCupId, IsAirfield) VALUES (?, 'Grass', ?, true)",
                locationTypeGuid, locationTypeCupId);

        Timestamp created = Timestamp.valueOf("2024-01-01 12:00:00");
        jdbc.update("""
                INSERT INTO Locations (
                    LocationId, LocationName, LocationShortName, CountryId, LocationTypeId,
                    IcaoCode, IsInboundRouteRequired, IsOutboundRouteRequired,
                    IsFastEntryRecord, CreatedOn, CreatedByUserId)
                VALUES (?, ?, 'J0C', ?, ?, 'JFIT', false, true, false, ?, ?)
                """,
                sharedLocationId, SHARED_NAME, countryId, locationTypeGuid, created, createdBy);

        // Both clubs reference the ONE shared Location via Clubs.HomebaseId — the
        // exact 2-club reference the J-0c legacy spec sets up.
        jdbc.update("INSERT INTO Clubs (ClubId, HomebaseId) VALUES (?, ?)", clubA, sharedLocationId);
        jdbc.update("INSERT INTO Clubs (ClubId, HomebaseId) VALUES (?, ?)", clubB, sharedLocationId);

        // One shared child IOP at the shared Location — fans out across the same
        // partner set as its parent.
        jdbc.update("""
                INSERT INTO InOutboundPoints (
                    InOutboundPointId, LocationId, InOutboundPointName,
                    IsInboundPoint, IsOutboundPoint, CreatedOn, CreatedByUserId)
                VALUES (?, ?, '07N', true, false, ?, ?)
                """,
                sharedIopId, sharedLocationId, created, createdBy);
    }

    @AfterEach
    void dropStagingTables() {
        clearStaging();
        jdbc.execute("DROP TABLE IF EXISTS InOutboundPoints");
        jdbc.execute("DROP TABLE IF EXISTS Flights");
        jdbc.execute("DROP TABLE IF EXISTS Clubs");
        jdbc.execute("DROP TABLE IF EXISTS Locations");
        jdbc.execute("DROP TABLE IF EXISTS LocationTypes");
    }

    private void clearStaging() {
        jdbc.update("DELETE FROM InOutboundPoints");
        jdbc.update("DELETE FROM Flights");
        jdbc.update("DELETE FROM Clubs");
        jdbc.update("DELETE FROM Locations");
        jdbc.update("DELETE FROM LocationTypes");
    }

    @Test
    void producerSelectFansSharedLocationOutToOneRowPerReferencingClub() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.LOCATION);

        List<Map<String, Object>> rows = jdbc.queryForList(select);

        // The ONE shared Location fans out to exactly TWO rows — one per club.
        assertThat(rows)
                .as("the shared legacy Location referenced by 2 clubs (both via "
                        + "Clubs.HomebaseId) fans out to one producer row per club")
                .hasSize(2);

        List<UUID> clubIds = rows.stream()
                .map(r -> UUID.fromString(r.get("clubid").toString()))
                .sorted()
                .toList();
        assertThat(clubIds)
                .as("BOTH referencing clubs get a fanned-out copy — club-B's row is "
                        + "NOT dropped (the fanout RED: club-B can't find its copy)")
                .containsExactlyInAnyOrder(clubA, clubB);

        // Both replicas carry the SAME location name (one shared legacy Location),
        // so each club sees its own copy under the random name the spec asserts on.
        assertThat(rows.stream()
                        .map(r -> r.get("locationname").toString())
                        .collect(Collectors.toSet()))
                .as("both fanned-out replicas carry the SAME name — the freshness "
                        + "token the parity spec locates each club's copy by")
                .containsExactly(SHARED_NAME);

        // The two replicas project DISTINCT fan-out ids, derived per (LocationId,
        // legacy ClubId) — else they would PK-collide on t_location.id.
        Map<UUID, UUID> idByClub = new java.util.HashMap<>();
        for (Map<String, Object> r : rows) {
            // writeNdjson derives the per-replica id from (LocationId, ClubId);
            // the SELECT projects LocationId + the partner ClubId verbatim.
            UUID club = UUID.fromString(r.get("clubid").toString());
            idByClub.put(club, Coercions.deriveFanOutId(sharedLocationId, club));
        }
        assertThat(idByClub.get(clubA))
                .as("the two replicas derive DISTINCT fan-out ids")
                .isNotEqualTo(idByClub.get(clubB));

        // Every row resolves its LocationType via the INNER JOIN to LocationTypes,
        // projecting the CupId the mapper reads — not dropped by the join.
        assertThat(rows.stream()
                        .map(r -> ((Number) r.get("locationtypeid")).intValue())
                        .collect(Collectors.toSet()))
                .as("the LocationTypes INNER JOIN keeps both rows + projects the CupId")
                .containsExactly(locationTypeCupId);
    }

    @Test
    void producerSelectFansSharedChildIopOutToOneRowPerReferencingClub() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.INOUTBOUND_POINT);

        List<Map<String, Object>> rows = jdbc.queryForList(select);

        // The shared child IOP fans out to one row per club — each carries its OWN
        // legacy club id (the resolver-only wire field) so the (location_id,
        // club_id) composite FK lands on that club's own parent replica.
        assertThat(rows)
                .as("the shared child IOP fans out to one producer row per "
                        + "referencing club (mirrors its parent Location's fan-out)")
                .hasSize(2);

        List<UUID> clubIds = rows.stream()
                .map(r -> UUID.fromString(r.get("clubid").toString()))
                .sorted()
                .toList();
        assertThat(clubIds)
                .as("BOTH clubs get a fanned-out child IOP — club-B's child is not dropped")
                .containsExactlyInAnyOrder(clubA, clubB);
    }
}

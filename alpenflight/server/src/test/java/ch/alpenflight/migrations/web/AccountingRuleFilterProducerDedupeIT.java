package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.sql.Timestamp;
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
 * J-8 T-10 — the AccountingRuleFilter producer-side collision/renumber proof.
 *
 * <p>V4's {@code ux_arf_club_sort_partial} partial UNIQUE
 * ({@code (operating_club_id, sort_indicator) WHERE deleted_on IS NULL}) forbids
 * per-club {@code sort_indicator} duplicates, but the legacy
 * {@code AccountingRuleFilters.SortIndicator} is dup-prone {@code int NULL} — the
 * legacy has no such constraint. ACCOUNTING_RULE_FILTER is NOT fan-out, so two
 * legacy rows in ONE club sharing a {@code SortIndicator} (or a NULL one) resolve
 * to the SAME provisioned club → the 2nd INSERT violates
 * {@code ux_arf_club_sort_partial} with {@code sqlstate 23505} — the §4 fanout
 * gate blocker the synth bundle never catches (it aliases columns and never runs
 * the producer SELECT, {@code project_synth_bundle_doesnt_validate_producer_select}).
 *
 * <p>The fix is the renumber the {@code AccountingRuleFilterMapper} Javadoc
 * promises: the bound producer SELECT assigns each LIVE row a dense, distinct
 * {@code sort_indicator} per club via {@code ROW_NUMBER() OVER (PARTITION BY
 * ClubId ORDER BY SortIndicator, CreatedOn, AccountingRuleFilterId)} over the
 * rows the partial index covers ({@code WHERE IsDeleted = 0}), so the duplicate
 * never reaches the ingest with a colliding indicator. This IT runs the REAL
 * bound producer SELECT ({@link MapperLegacyBindings#selectForProducer}) against
 * a legacy-shaped {@code AccountingRuleFilters} staging table seeded with two
 * collision rows + a NULL-indicator row + a soft-deleted row in one club, and a
 * distinct row + a dirty-target row in another club, and asserts the renumber +
 * soft-delete-drop + the dirty-target gate.
 *
 * <p><strong>Dirty-target gate.</strong> The real legacy
 * {@code ArticleTarget}/{@code RecipientTarget} {@code nvarchar(max)} columns
 * hold non-JSON values ({@code ''}, {@code 'null'}, stray text) wherever the C#
 * layer wrote no target. Bare {@code JSON_VALUE} over those aborts the WHOLE
 * cursor on MSSQL with error 13609 ("JSON text is not properly formatted") — the
 * §4 fanout blocker the synth bundle's aliased columns never surface. The
 * producer SELECT gates each extraction behind a structural JSON-shape check so a
 * non-object/array value yields a NULL target instead of aborting; the staging
 * columns here are {@code TEXT} (the real {@code nvarchar(max)}, not jsonb) so the
 * dirty value can be seeded.
 *
 * <p><strong>Requires PostgreSQL 17 (CI's pinned container + the real legacy
 * MSSQL producer).</strong> The producer SELECT extracts the scalar
 * article/recipient targets via SQL/JSON {@code JSON_VALUE}, which is native to
 * MSSQL (the real producer) and PG 17 (CI). It is NOT available on PG 15, so a
 * dev box running the suite against an older LAN Postgres must run this IT
 * against the PG-17 Testcontainer ({@code ALPENFLIGHT_TEST_FORCE_DOCKER=1}); CI
 * always takes the PG-17 path. The round-trip ingest of the renumbered output is
 * proven by {@link AccountingRuleFilterMigrationRoundTripIT}.
 */
@Tag("slow")
class AccountingRuleFilterProducerDedupeIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private final UUID clubA = UUID.randomUUID();
    private final UUID clubB = UUID.randomUUID();

    // Two live rows in clubA sharing SortIndicator 5 — collide on the partial UNIQUE.
    private final UUID dupEarlier = UUID.randomUUID();
    private final UUID dupLater = UUID.randomUUID();
    // A live row in clubA with a NULL SortIndicator — legacy permits it; the
    // renumber must give it a concrete distinct index (NULLs sort first).
    private final UUID nullSortRow = UUID.randomUUID();
    // A soft-deleted (IsDeleted=1) row in clubA — must be DROPPED (the partial
    // index covers only deleted_on IS NULL; including it would consume a live
    // index / collide).
    private final UUID softDeletedRow = UUID.randomUUID();
    // A live row in clubB also with SortIndicator 5 — a DIFFERENT club, so NOT a
    // collision; must survive untouched (the renumber partitions on ClubId).
    private final UUID otherClubRow = UUID.randomUUID();
    // A live row in clubB whose ArticleTarget/RecipientTarget are dirty non-JSON
    // strings (the real legacy '' the C# layer leaves when no target is set).
    // Bare JSON_VALUE over these aborts the WHOLE cursor on MSSQL (error 13609);
    // the structural JSON-shape gate must yield NULL targets instead.
    private final UUID dirtyTargetRow = UUID.randomUUID();

    @BeforeEach
    void seedLegacyShapedStagingTable() {
        // The bound producer SELECT extracts the JSON-blob target scalars via
        // SQL/JSON JSON_VALUE — native to MSSQL (the real producer) + PG 17 (CI's
        // pinned container) but absent before PG 15. A dev box pointed at an older
        // LAN Postgres SKIPS this IT (not fails); CI's PG-17 container always runs
        // it, and a local run can force PG 17 via ALPENFLIGHT_TEST_FORCE_DOCKER=1.
        Integer pgMajor = jdbc.queryForObject(
                "SELECT current_setting('server_version_num')::int / 10000", Integer.class);
        assumeTrue(pgMajor != null && pgMajor >= 17,
                "AccountingRuleFilter producer SELECT uses SQL/JSON JSON_VALUE — "
                        + "requires PostgreSQL 17 (CI container + the real MSSQL producer); "
                        + "current major = " + pgMajor);

        // A legacy-shaped staging table standing in for the MSSQL
        // AccountingRuleFilters the producer SELECT reads. Unquoted mixed-case
        // identifiers fold to lowercase in Postgres, matching the unquoted names
        // in the bound SELECT. ArticleTarget/RecipientTarget are TEXT (the real
        // legacy nvarchar(max)) — NOT jsonb — so a dirty non-JSON value ('',
        // 'null', stray text) the C# layer left can be seeded, exercising the
        // structural JSON-shape gate the producer SELECT applies before JSON_VALUE.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS AccountingRuleFilters (
                    AccountingRuleFilterId UUID PRIMARY KEY,
                    ClubId                 UUID NOT NULL,
                    AccountingRuleFilterTypeId INTEGER NOT NULL,
                    AccountingUnitTypeId   INTEGER,
                    RuleFilterName         TEXT,
                    Description            TEXT,
                    IsActive               BOOLEAN NOT NULL,
                    SortIndicator          INTEGER,
                    StopRuleEngineWhenRuleApplied BOOLEAN NOT NULL,
                    IsChargedToClubInternal       BOOLEAN NOT NULL,
                    ArticleTarget          TEXT,
                    RecipientTarget        TEXT,
                    IsRuleForGliderFlights BOOLEAN NOT NULL,
                    IsRuleForTowingFlights BOOLEAN NOT NULL,
                    IsRuleForMotorFlights  BOOLEAN NOT NULL,
                    NoLandingTaxForGlider  BOOLEAN NOT NULL,
                    NoLandingTaxForTowingAircraft BOOLEAN NOT NULL,
                    NoLandingTaxForAircraft       BOOLEAN NOT NULL,
                    IncludeFlightTypeName  BOOLEAN NOT NULL,
                    ExtendMatchingFlightTypeCodesToGliderAndTowFlight BOOLEAN NOT NULL,
                    IncludeThresholdText   BOOLEAN NOT NULL,
                    ThresholdText          TEXT,
                    MinFlightTimeInSecondsMatchingValue INTEGER,
                    MaxFlightTimeInSecondsMatchingValue INTEGER,
                    MinEngineTimeInSecondsMatchingValue INTEGER,
                    MaxEngineTimeInSecondsMatchingValue INTEGER,
                    UseRuleForAllAircraftsExceptListed   BOOLEAN NOT NULL,
                    MatchedAircraftImmatriculations      TEXT,
                    UseRuleForAllStartTypesExceptListed  BOOLEAN NOT NULL,
                    MatchedStartTypes      TEXT,
                    UseRuleForAllFlightTypesExceptListed BOOLEAN NOT NULL,
                    MatchedFlightTypeCodes TEXT,
                    UseRuleForAllStartLocationsExceptListed BOOLEAN NOT NULL,
                    MatchedStartLocations  TEXT,
                    UseRuleForAllLdgLocationsExceptListed BOOLEAN NOT NULL,
                    MatchedLdgLocations    TEXT,
                    UseRuleForAllClubMemberNumbersExceptListed BOOLEAN NOT NULL,
                    MatchedClubMemberNumbers TEXT,
                    UseRuleForAllFlightCrewTypesExceptListed BOOLEAN NOT NULL,
                    MatchedFlightCrewTypes TEXT,
                    UseRuleForAllAircraftsOnHomebaseExceptListed BOOLEAN NOT NULL,
                    MatchedAircraftsHomebase TEXT,
                    UseRuleForAllMemberStatesExceptListed BOOLEAN NOT NULL,
                    MatchedMemberStates    TEXT,
                    UseRuleForAllPersonCategoriesExceptListed BOOLEAN NOT NULL,
                    MatchedPersonCategories TEXT,
                    CreatedOn              TIMESTAMP NOT NULL,
                    CreatedByUserId        UUID,
                    ModifiedOn             TIMESTAMP,
                    ModifiedByUserId       UUID,
                    DeletedOn              TIMESTAMP,
                    DeletedByUserId        UUID,
                    IsDeleted              INTEGER NOT NULL DEFAULT 0
                )
                """);
        jdbc.update("DELETE FROM AccountingRuleFilters");

        // clubA: two live rows sharing SortIndicator 5 + a NULL-indicator row.
        insertRow(dupEarlier, clubA, 5, Timestamp.valueOf("2020-01-01 08:00:00"), 0,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        insertRow(dupLater, clubA, 5, Timestamp.valueOf("2021-06-15 09:30:00"), 0,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        insertRow(nullSortRow, clubA, null, Timestamp.valueOf("2019-01-01 00:00:00"), 0,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        // clubA: a soft-deleted row also at indicator 5 — must be dropped.
        insertRow(softDeletedRow, clubA, 5, Timestamp.valueOf("2018-01-01 00:00:00"), 1,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        // clubB: a live row at indicator 5 — a different club, NOT a collision.
        insertRow(otherClubRow, clubB, 5, Timestamp.valueOf("2020-01-01 08:00:00"), 0,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        // clubB: a live row whose targets are the dirty legacy '' — bare JSON_VALUE
        // would abort the entire cursor (MSSQL 13609); the gate must NULL them.
        insertRow(dirtyTargetRow, clubB, 7, Timestamp.valueOf("2020-02-01 08:00:00"), 0,
                "", "");
    }

    private static final String ARTICLE_TARGET_JSON =
            "{\"ArticleNumber\":\"4001\",\"DeliveryLineText\":\"Flight time charge\"}";
    private static final String RECIPIENT_TARGET_JSON =
            "{\"PersonClubMemberNumber\":\"1042\",\"RecipientName\":\"Club Treasurer\"}";

    @AfterEach
    void dropStagingTable() {
        jdbc.execute("DROP TABLE IF EXISTS AccountingRuleFilters");
    }

    @Test
    void producerSelectRenumbersSortIndicatorCollisionsPerClubAndDropsSoftDeleted() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.ACCOUNTING_RULE_FILTER);

        List<Map<String, Object>> rows = jdbc.queryForList(select);

        // The soft-deleted row is dropped; 5 live rows survive (3 in clubA + 2 in clubB).
        List<UUID> survivingIds = rows.stream()
                .map(r -> UUID.fromString(r.get("accountingrulefilterid").toString()))
                .sorted()
                .toList();
        assertThat(survivingIds)
                .as("the soft-deleted row (IsDeleted=1) is dropped — the partial UNIQUE "
                        + "covers only deleted_on IS NULL; the 5 live rows survive")
                .containsExactlyInAnyOrder(
                        dupEarlier, dupLater, nullSortRow, otherClubRow, dirtyTargetRow)
                .doesNotContain(softDeletedRow);

        // The 3 live clubA rows each carry a DISTINCT sort_indicator post-renumber —
        // so the 2nd/3rd INSERT no longer 23505s on ux_arf_club_sort_partial.
        List<Integer> clubASortIndicators = rows.stream()
                .filter(r -> r.get("clubid").toString().equals(clubA.toString()))
                .map(r -> ((Number) r.get("sortindicator")).intValue())
                .sorted()
                .toList();
        assertThat(clubASortIndicators)
                .as("clubA's 3 live rows are renumbered to dense, DISTINCT indicators "
                        + "1..N (collision + NULL resolved) — else the 2nd would 23505")
                .containsExactly(1, 2, 3);

        // The earlier-CreatedOn dup keeps a lower index than the later dup (ORDER BY
        // SortIndicator, CreatedOn). The NULL-indicator row sorts first (NULLs first
        // in the SortIndicator ASC ordering) → index 1.
        Map<UUID, Integer> indicatorById = new java.util.HashMap<>();
        for (Map<String, Object> r : rows) {
            indicatorById.put(UUID.fromString(r.get("accountingrulefilterid").toString()),
                    ((Number) r.get("sortindicator")).intValue());
        }
        assertThat(indicatorById.get(nullSortRow))
                .as("the NULL-SortIndicator row sorts first → index 1")
                .isEqualTo(1);
        assertThat(indicatorById.get(dupEarlier))
                .as("the earlier-CreatedOn collision row precedes the later one")
                .isLessThan(indicatorById.get(dupLater));

        // clubB's rows are untouched by clubA's renumber (partition on ClubId) — they
        // get their own per-club indices 1..2.
        assertThat(List.of(indicatorById.get(otherClubRow), indicatorById.get(dirtyTargetRow)))
                .as("clubB's rows are renumbered within their OWN club (partition on ClubId)")
                .containsExactlyInAnyOrder(1, 2);

        // The dirty-target row survives with NULL targets — bare JSON_VALUE over its
        // '' ArticleTarget/RecipientTarget would have aborted the WHOLE cursor
        // (MSSQL 13609); the structural JSON-shape gate NULLs them instead. This is
        // the real-legacy dirty value (the 100-Insert N'' rows) the synth bundle's
        // aliased columns never exercise.
        Map<String, Object> dirty = rows.stream()
                .filter(r -> r.get("accountingrulefilterid").toString()
                        .equals(dirtyTargetRow.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(dirty.get("articletarget"))
                .as("a non-JSON ArticleTarget extracts to NULL, not an aborted export")
                .isNull();
        assertThat(dirty.get("deliverylinetext")).isNull();
        assertThat(dirty.get("recipienttarget"))
                .as("a non-JSON RecipientTarget extracts to NULL, not an aborted export")
                .isNull();
        assertThat(dirty.get("recipientname")).isNull();

        // The JSON-blob target scalars are extracted via JSON_VALUE (PG 17 / MSSQL).
        Map<String, Object> earlier = rows.stream()
                .filter(r -> r.get("accountingrulefilterid").toString().equals(dupEarlier.toString()))
                .findFirst()
                .orElseThrow();
        assertThat(earlier.get("articletarget"))
                .as("ArticleTarget.ArticleNumber extracted from the JSON blob (not the blob)")
                .isEqualTo("4001");
        assertThat(earlier.get("deliverylinetext"))
                .as("ArticleTarget.DeliveryLineText extracted to its own column for filter_config")
                .isEqualTo("Flight time charge");
        assertThat(earlier.get("recipienttarget"))
                .as("RecipientTarget.PersonClubMemberNumber extracted from the JSON blob")
                .isEqualTo("1042");
        assertThat(earlier.get("recipientname"))
                .as("RecipientTarget.RecipientName extracted to its own column for filter_config")
                .isEqualTo("Club Treasurer");
    }

    private void insertRow(UUID id, UUID club, Integer sortIndicator, Timestamp createdOn,
            int isDeleted, String articleTarget, String recipientTarget) {
        jdbc.update("""
                INSERT INTO AccountingRuleFilters (
                    AccountingRuleFilterId, ClubId, AccountingRuleFilterTypeId,
                    AccountingUnitTypeId, RuleFilterName, Description, IsActive,
                    SortIndicator, StopRuleEngineWhenRuleApplied, IsChargedToClubInternal,
                    ArticleTarget, RecipientTarget,
                    IsRuleForGliderFlights, IsRuleForTowingFlights, IsRuleForMotorFlights,
                    NoLandingTaxForGlider, NoLandingTaxForTowingAircraft, NoLandingTaxForAircraft,
                    IncludeFlightTypeName, ExtendMatchingFlightTypeCodesToGliderAndTowFlight,
                    IncludeThresholdText, ThresholdText,
                    MinFlightTimeInSecondsMatchingValue, MaxFlightTimeInSecondsMatchingValue,
                    MinEngineTimeInSecondsMatchingValue, MaxEngineTimeInSecondsMatchingValue,
                    UseRuleForAllAircraftsExceptListed, MatchedAircraftImmatriculations,
                    UseRuleForAllStartTypesExceptListed, MatchedStartTypes,
                    UseRuleForAllFlightTypesExceptListed, MatchedFlightTypeCodes,
                    UseRuleForAllStartLocationsExceptListed, MatchedStartLocations,
                    UseRuleForAllLdgLocationsExceptListed, MatchedLdgLocations,
                    UseRuleForAllClubMemberNumbersExceptListed, MatchedClubMemberNumbers,
                    UseRuleForAllFlightCrewTypesExceptListed, MatchedFlightCrewTypes,
                    UseRuleForAllAircraftsOnHomebaseExceptListed, MatchedAircraftsHomebase,
                    UseRuleForAllMemberStatesExceptListed, MatchedMemberStates,
                    UseRuleForAllPersonCategoriesExceptListed, MatchedPersonCategories,
                    CreatedOn, CreatedByUserId, ModifiedOn, ModifiedByUserId,
                    DeletedOn, DeletedByUserId, IsDeleted)
                VALUES (?, ?, 30, NULL, 'Flight time', NULL, true,
                        ?, false, false,
                        ?, ?,
                        true, false, false,
                        false, false, false,
                        false, false,
                        false, NULL,
                        NULL, NULL, NULL, NULL,
                        false, '["HB-1234","HB-5678"]',
                        true, '[]',
                        false, '["STD"]',
                        false, NULL,
                        false, NULL,
                        false, '[1001,1002]',
                        false, NULL,
                        true, 'null',
                        false, NULL,
                        false, NULL,
                        ?, NULL, ?, NULL,
                        ?, NULL, ?)
                """,
                id, club,
                sortIndicator,
                articleTarget,
                recipientTarget,
                createdOn, createdOn,
                isDeleted == 1 ? Timestamp.valueOf("2022-01-01 00:00:00") : null,
                isDeleted);
    }
}

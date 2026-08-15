package ch.alpenflight.migrations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.migration.bundle.accounting.AccountingRuleFilterMapper;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
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

@Tag("slow")
class AccountingRuleFilterProducerDedupeIT extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private static final int LIVE = 0;
    private static final int SOFT_DELETED = 1;
    private static final int COLLIDING_LEGACY_SORT_INDICATOR = 5;
    private static final String DIRTY_NON_JSON_TARGET_LEFT_BY_THE_LEGACY_LAYER = "";
    private static final long LEGACY_MIN_FLIGHT_TIME_SECONDS_BILLS_FROM_ZERO = 0L;
    private static final long LEGACY_UNBOUNDED_MAX_FLIGHT_TIME_SENTINEL = Long.MAX_VALUE;
    private static final int MINIMUM_SUPPORTED_POSTGRES_MAJOR_FOR_SQL_JSON_VALUE = 17;

    private final UUID clubA = UUID.randomUUID();
    private final UUID clubB = UUID.randomUUID();

    private final UUID dupEarlier = UUID.randomUUID();
    private final UUID dupLater = UUID.randomUUID();
    private final UUID nullSortRow = UUID.randomUUID();
    private final UUID softDeletedRow = UUID.randomUUID();
    private final UUID otherClubRow = UUID.randomUUID();
    private final UUID dirtyTargetRow = UUID.randomUUID();
    private final UUID gliderFlightTimeRow = UUID.randomUUID();

    @BeforeEach
    void seedLegacyShapedStagingTable() {
        Integer pgMajor = jdbc.queryForObject(
                "SELECT current_setting('server_version_num')::int / 10000", Integer.class);
        assumeTrue(pgMajor != null && pgMajor >= MINIMUM_SUPPORTED_POSTGRES_MAJOR_FOR_SQL_JSON_VALUE,
                "AccountingRuleFilter producer SELECT uses SQL/JSON JSON_VALUE — "
                        + "requires PostgreSQL 17 (CI container + the real MSSQL producer); "
                        + "current major = " + pgMajor);

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
                    MinFlightTimeInSecondsMatchingValue BIGINT,
                    MaxFlightTimeInSecondsMatchingValue BIGINT,
                    MinEngineTimeInSecondsMatchingValue BIGINT,
                    MaxEngineTimeInSecondsMatchingValue BIGINT,
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

        insertRow(dupEarlier, clubA, COLLIDING_LEGACY_SORT_INDICATOR,
                Timestamp.valueOf("2020-01-01 08:00:00"), LIVE,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        insertRow(dupLater, clubA, COLLIDING_LEGACY_SORT_INDICATOR,
                Timestamp.valueOf("2021-06-15 09:30:00"), LIVE,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        insertRow(nullSortRow, clubA, null, Timestamp.valueOf("2019-01-01 00:00:00"), LIVE,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        insertRow(softDeletedRow, clubA, COLLIDING_LEGACY_SORT_INDICATOR,
                Timestamp.valueOf("2018-01-01 00:00:00"), SOFT_DELETED,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        insertRow(otherClubRow, clubB, COLLIDING_LEGACY_SORT_INDICATOR,
                Timestamp.valueOf("2020-01-01 08:00:00"), LIVE,
                ARTICLE_TARGET_JSON, RECIPIENT_TARGET_JSON);
        insertRow(dirtyTargetRow, clubB, 7, Timestamp.valueOf("2020-02-01 08:00:00"), LIVE,
                DIRTY_NON_JSON_TARGET_LEFT_BY_THE_LEGACY_LAYER,
                DIRTY_NON_JSON_TARGET_LEFT_BY_THE_LEGACY_LAYER);
        insertGliderFlightTimeRow(gliderFlightTimeRow, clubB, 9,
                Timestamp.valueOf("2020-03-01 08:00:00"));
    }

    private static final String ARTICLE_TARGET_JSON =
            "{\"ArticleNumber\":\"4001\",\"DeliveryLineText\":\"Flight time charge\"}";
    private static final String GLIDER_ARTICLE_TARGET_JSON =
            "{\"ArticleNumber\":\"5001\",\"DeliveryLineText\":\"Glider flight minutes\"}";
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

        List<UUID> survivingIds = rows.stream()
                .map(r -> UUID.fromString(r.get("accountingrulefilterid").toString()))
                .sorted()
                .toList();
        assertThat(survivingIds)
                .as("the soft-deleted row (IsDeleted=1) is dropped — the partial UNIQUE "
                        + "covers only deleted_on IS NULL; the 6 live rows survive")
                .containsExactlyInAnyOrder(
                        dupEarlier, dupLater, nullSortRow,
                        otherClubRow, dirtyTargetRow, gliderFlightTimeRow)
                .doesNotContain(softDeletedRow);

        List<Integer> clubASortIndicators = rows.stream()
                .filter(r -> r.get("clubid").toString().equals(clubA.toString()))
                .map(r -> ((Number) r.get("sortindicator")).intValue())
                .sorted()
                .toList();
        assertThat(clubASortIndicators)
                .as("clubA's 3 live rows are renumbered to dense, DISTINCT indicators "
                        + "1..N (collision + NULL resolved) — else the 2nd would 23505")
                .containsExactly(1, 2, 3);

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

        assertThat(List.of(indicatorById.get(otherClubRow), indicatorById.get(dirtyTargetRow),
                indicatorById.get(gliderFlightTimeRow)))
                .as("clubB's rows are renumbered within their OWN club (partition on ClubId)")
                .containsExactlyInAnyOrder(1, 2, 3);

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

    @Test
    void migratedGliderFlightTimeFilterPredicateRoundTripsThroughTheProducerSelectAndMapper()
            throws Exception {
        String select = MapperLegacyBindings.selectForProducer(EntityType.ACCOUNTING_RULE_FILTER);
        AccountingRuleFilterMapper mapper = new AccountingRuleFilterMapper();
        ObjectMapper json = new ObjectMapper();

        JsonNode config = jdbc.query(select, (ResultSet rs) -> {
            try {
                while (rs.next()) {
                    if (!gliderFlightTimeRow.toString()
                            .equals(rs.getString("AccountingRuleFilterId"))) {
                        continue;
                    }
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    try (JsonGenerator gen = json.getFactory().createGenerator(sink)) {
                        mapper.writeNdjson(rs, gen);
                    }
                    return json.readTree(sink.toByteArray()).get("filter_config");
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "mapper.writeNdjson over the producer SELECT row failed — the legacy "
                                + "BIGINT time columns must read as Long, not Integer", e);
            }
            return null;
        });

        assertThat(config)
                .as("the glider FlightTime row survives the producer SELECT → mapper (the "
                        + "BIGINT min/max read as Long, not the Integer that 23-aborts the cursor)")
                .isNotNull();
        assertThat(config.get("isRuleForGliderFlights").asBoolean())
                .as("glider scope round-trips so the engine matches a glider flight")
                .isTrue();
        assertThat(config.get("isRuleForTowingFlights").asBoolean()).isFalse();
        assertThat(config.get("isRuleForMotorFlights").asBoolean()).isFalse();
        assertThat(config.get("minFlightTimeInSecondsMatchingValue").asInt())
                .as("min=0 round-trips so the engine's min-exclusive window admits the whole "
                        + "active duration (bills from second 0)")
                .isZero();
        assertThat(config.get("maxFlightTimeInSecondsMatchingValue").asInt())
                .as("the legacy Long.MAX unbounded sentinel clamps to Integer.MAX — still "
                        + "unbounded for any real flight duration")
                .isEqualTo(Integer.MAX_VALUE);
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

    private void insertGliderFlightTimeRow(UUID id, UUID club, int sortIndicator,
            Timestamp createdOn) {
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
                VALUES (?, ?, 30, 10, 'FlightTime: Glider per minute', NULL, true,
                        ?, false, false,
                        ?, NULL,
                        true, false, false,
                        false, false, false,
                        false, false,
                        false, NULL,
                        ?, ?,
                        NULL, NULL,
                        true, '[]',
                        true, '[]',
                        true, '[]',
                        true, '[]',
                        true, '[]',
                        true, '[]',
                        true, '[]',
                        true, 'null',
                        true, '[]',
                        true, '[]',
                        ?, NULL, ?, NULL,
                        NULL, NULL, 0)
                """,
                id, club, sortIndicator, GLIDER_ARTICLE_TARGET_JSON,
                LEGACY_MIN_FLIGHT_TIME_SECONDS_BILLS_FROM_ZERO,
                LEGACY_UNBOUNDED_MAX_FLIGHT_TIME_SENTINEL,
                createdOn, createdOn);
    }
}

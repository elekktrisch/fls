package ch.alpenflight.migration.bundle.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class AccountingRuleFilterMapperTest
        extends AbstractMapperContractTest<AccountingRuleFilterMapper> {

    private final AccountingRuleFilterMapper mapper = new AccountingRuleFilterMapper();

    @Override
    protected AccountingRuleFilterMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("AccountingRuleFilterId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("AccountingRuleFilterTypeId", 10);
        row.put("AccountingUnitTypeId", 20);
        row.put("RuleFilterName", "Recipient — Club Members");
        row.put("Description", faker.lorem().sentence());
        row.put("IsActive", true);
        row.put("SortIndicator", 100);
        row.put("StopRuleEngineWhenRuleApplied", false);
        row.put("IsChargedToClubInternal", false);
        row.put("ArticleTarget", "DEFAULT");
        row.put("RecipientTarget", "PILOT");

        row.put("IsRuleForGliderFlights", true);
        row.put("IsRuleForTowingFlights", false);
        row.put("IsRuleForMotorFlights", false);

        row.put("NoLandingTaxForGlider", false);
        row.put("NoLandingTaxForTowingAircraft", false);
        row.put("NoLandingTaxForAircraft", false);

        row.put("IncludeFlightTypeName", true);
        row.put("ExtendMatchingFlightTypeCodesToGliderAndTowFlight", false);
        row.put("IncludeThresholdText", false);
        row.put("ThresholdText", "Up to 30 minutes");

        row.put("MinFlightTimeInSecondsMatchingValue", 0);
        row.put("MaxFlightTimeInSecondsMatchingValue", 1800);
        row.put("MinEngineTimeInSecondsMatchingValue", null);
        row.put("MaxEngineTimeInSecondsMatchingValue", null);

        // Boolean-pair predicates: inversion flag + comma-separated list.
        row.put("UseRuleForAllAircraftsExceptListed", false);
        row.put("MatchedAircraftImmatriculations", "HB-1234, HB-5678 , HB-9999");
        row.put("UseRuleForAllStartTypesExceptListed", true);
        row.put("MatchedStartTypes", "");
        row.put("UseRuleForAllFlightTypesExceptListed", false);
        row.put("MatchedFlightTypeCodes", "STD,TRAIN");
        row.put("UseRuleForAllStartLocationsExceptListed", false);
        row.put("MatchedStartLocations", null);
        row.put("UseRuleForAllLdgLocationsExceptListed", false);
        row.put("MatchedLdgLocations", "LSZH");
        row.put("UseRuleForAllClubMemberNumbersExceptListed", false);
        row.put("MatchedClubMemberNumbers", "1001,1002,1003");
        row.put("UseRuleForAllFlightCrewTypesExceptListed", false);
        row.put("MatchedFlightCrewTypes", "PILOT");
        row.put("UseRuleForAllAircraftsOnHomebaseExceptListed", true);
        row.put("MatchedAircraftsHomebase", "");
        row.put("UseRuleForAllMemberStatesExceptListed", false);
        row.put("MatchedMemberStates", "ACTIVE,PASSIVE");
        row.put("UseRuleForAllPersonCategoriesExceptListed", false);
        row.put("MatchedPersonCategories", "VOLUNTEER");

        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesAccountingRuleFilterEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.ACCOUNTING_RULE_FILTER);
    }

    @Test
    void declaresOnlyClubAsForeignKey() {
        // filter_type_id + accounting_unit_type_id resolve via V4-seeded
        // legacy_int_id — outside EntityType.
        assertThat(mapper.foreignKeys()).containsExactly(EntityType.CLUB);
    }

    @Test
    void filterConfigJsonbCarriesScalarPredicatesAndPairedShapes() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        JsonNode config = emitted.get(AccountingRuleFilterMapper.FILTER_CONFIG);

        assertThat(config.isObject())
                .as("filter_config must be a JSON object — Jackson default-typing is "
                        + "disabled globally so no @class / @type keys at the root")
                .isTrue();

        assertThat(config.has("@class") || config.has("@type"))
                .as("A03 mitigation — polymorphic type tags must not leak into the jsonb shape")
                .isFalse();

        assertThat(config.get("isRuleForGliderFlights").asBoolean()).isTrue();
        assertThat(config.get("isRuleForTowingFlights").asBoolean()).isFalse();
        assertThat(config.get("thresholdText").asText()).isEqualTo("Up to 30 minutes");
        assertThat(config.get("minFlightTimeInSecondsMatchingValue").asInt()).isZero();
        assertThat(config.get("maxFlightTimeInSecondsMatchingValue").asInt()).isEqualTo(1800);

        JsonNode aircraftPair = config.get("aircraftImmatriculations");
        assertThat(aircraftPair.get("useAllExcept").asBoolean()).isFalse();
        assertThat(aircraftPair.get("matched"))
                .as("Comma-separated list trims surrounding whitespace and drops blanks")
                .extracting(JsonNode::asText)
                .containsExactly("HB-1234", "HB-5678", "HB-9999");

        JsonNode startTypePair = config.get("startTypes");
        assertThat(startTypePair.get("useAllExcept").asBoolean()).isTrue();
        assertThat(startTypePair.get("matched"))
                .as("Empty comma-separated list yields empty matched array, "
                        + "preserving the inversion (use-all-except with nothing listed)")
                .isEmpty();

        JsonNode startLocationsPair = config.get("startLocations");
        assertThat(startLocationsPair.get("matched"))
                .as("NULL legacy list yields empty matched array")
                .isEmpty();
    }
}

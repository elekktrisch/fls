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
        // The producer SELECT extracts these scalars from the legacy JSON blobs
        // via JSON_VALUE, so the cursor delivers the bare scalar (not the blob):
        // ArticleTarget = ArticleNumber, RecipientTarget = member-number, and
        // DeliveryLineText / RecipientName ride filter_config.
        row.put("ArticleTarget", "4001");
        row.put("DeliveryLineText", "Flight time charge");
        row.put("RecipientTarget", "1042");
        row.put("RecipientName", "Club Treasurer");

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

        // Boolean-pair predicates: inversion flag + JSON-array list (the legacy
        // C# serialises these as JsonConvert.SerializeObject(List<…>), so the
        // cursor delivers a JSON array string — strings, ints, and guids alike).
        row.put("UseRuleForAllAircraftsExceptListed", false);
        row.put("MatchedAircraftImmatriculations", "[\"HB-1234\",\"HB-5678\",\"HB-9999\"]");
        row.put("UseRuleForAllStartTypesExceptListed", true);
        row.put("MatchedStartTypes", "[]");
        row.put("UseRuleForAllFlightTypesExceptListed", false);
        row.put("MatchedFlightTypeCodes", "[\"STD\",\"TRAIN\"]");
        row.put("UseRuleForAllStartLocationsExceptListed", false);
        row.put("MatchedStartLocations", null);
        row.put("UseRuleForAllLdgLocationsExceptListed", false);
        row.put("MatchedLdgLocations", "[\"LSZH\"]");
        row.put("UseRuleForAllClubMemberNumbersExceptListed", false);
        // Legacy serialises List<int> for the int-typed match-lists — surfaces
        // as a uniform String[] (matching FilterConfig.MatchList).
        row.put("MatchedClubMemberNumbers", "[1001,1002,1003]");
        row.put("UseRuleForAllFlightCrewTypesExceptListed", false);
        row.put("MatchedFlightCrewTypes", "[1]");
        row.put("UseRuleForAllAircraftsOnHomebaseExceptListed", true);
        row.put("MatchedAircraftsHomebase", "null");
        row.put("UseRuleForAllMemberStatesExceptListed", false);
        row.put("MatchedMemberStates",
                "[\"a1b2c3d4-0000-0000-0000-000000000001\","
                        + "\"a1b2c3d4-0000-0000-0000-000000000002\"]");
        row.put("UseRuleForAllPersonCategoriesExceptListed", false);
        row.put("MatchedPersonCategories", "[\"00000000-0000-0000-0000-0000000000aa\"]");

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
        // legacy_int_id — outside EntityType (they ride referenceLookups()).
        assertThat(mapper.foreignKeys()).containsExactly(EntityType.CLUB);
    }

    @Test
    void resolvesFilterTypeAndUnitTypeViaReferenceLookups() {
        // T-10 (T-09 finding): without referenceLookups() the ingest leaves the
        // synthetic new UUID(0, legacyIntId) verbatim and the row FK-violates
        // fk_arf_filter_type_id at INSERT for EVERY type. The mapper resolves both
        // ref columns against their V4/V42-seeded legacy_int_id tables.
        assertThat(mapper.referenceLookups())
                .extracting(
                        ch.alpenflight.migration.bundle.ReferenceLookup::column,
                        ch.alpenflight.migration.bundle.ReferenceLookup::seedTable)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "filter_type_id", "t_accounting_rule_filter_type"),
                        org.assertj.core.groups.Tuple.tuple(
                                "accounting_unit_type_id", "t_accounting_unit_type"));
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

        // T-03/T-10: the descriptive text travelling with the targets, extracted
        // by the producer SELECT from the legacy JSON blobs, rides filter_config.
        assertThat(config.get("deliveryLineText").asText())
                .as("DeliveryLineText (from ArticleTarget JSON) rides filter_config")
                .isEqualTo("Flight time charge");
        assertThat(config.get("recipientName").asText())
                .as("RecipientName (from RecipientTarget JSON) rides filter_config")
                .isEqualTo("Club Treasurer");

        JsonNode aircraftPair = config.get("aircraftImmatriculations");
        assertThat(aircraftPair.get("useAllExcept").asBoolean()).isFalse();
        assertThat(aircraftPair.get("matched"))
                .as("Legacy JSON array (JsonConvert.SerializeObject(List<string>)) "
                        + "parses element-by-element; comma-split would corrupt it")
                .extracting(JsonNode::asText)
                .containsExactly("HB-1234", "HB-5678", "HB-9999");

        JsonNode memberNumbersPair = config.get("clubMemberNumbers");
        assertThat(memberNumbersPair.get("matched"))
                .as("Legacy List<int> serialises as [1001,1002,1003] → uniform String[]")
                .extracting(JsonNode::asText)
                .containsExactly("1001", "1002", "1003");

        JsonNode memberStatesPair = config.get("memberStates");
        assertThat(memberStatesPair.get("matched"))
                .as("Legacy List<Guid> serialises as a JSON array of guid strings")
                .extracting(JsonNode::asText)
                .containsExactly(
                        "a1b2c3d4-0000-0000-0000-000000000001",
                        "a1b2c3d4-0000-0000-0000-000000000002");

        JsonNode startTypePair = config.get("startTypes");
        assertThat(startTypePair.get("useAllExcept").asBoolean()).isTrue();
        assertThat(startTypePair.get("matched"))
                .as("Empty JSON array [] yields empty matched array, preserving the "
                        + "inversion (use-all-except with nothing listed)")
                .isEmpty();

        JsonNode startLocationsPair = config.get("startLocations");
        assertThat(startLocationsPair.get("matched"))
                .as("NULL legacy list yields empty matched array")
                .isEmpty();

        JsonNode homebasePair = config.get("aircraftHomebases");
        assertThat(homebasePair.get("matched"))
                .as("legacy JSON 'null' (JsonConvert.SerializeObject(null)) yields "
                        + "empty matched array")
                .isEmpty();
    }
}

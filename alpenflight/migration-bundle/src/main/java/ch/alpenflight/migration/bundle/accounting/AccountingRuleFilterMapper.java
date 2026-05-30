package ch.alpenflight.migration.bundle.accounting;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Tenant-scoped accounting-rule aggregate root: legacy
 * {@code AccountingRuleFilters.AccountingRuleFilterId} →
 * {@code t_accounting_rule_filter.id}. {@code operating_club_id} is the
 * {@code @TenantId} discriminator per V4.
 *
 * <p>{@code filter_type_id} + {@code accounting_unit_type_id} resolve via
 * the V4-seeded {@code legacy_int_id} maps
 * ({@link Coercions#legacyIntIdToUuidString} /
 * {@link Coercions#optionalLegacyIntIdAsUuidString}). The seeded value
 * sets are pinned in V4: filter-type {@code legacy_int_id ∈
 * {10,20,30,40,50,60,70,80}}, accounting-unit-type
 * {@code legacy_int_id ∈ {10,20,30,40}}.
 *
 * <p><strong>The {@code filter_config jsonb} fold.</strong> Legacy
 * {@code AccountingRuleFilters} carries 30+ per-rule-type predicate
 * columns ({@code MatchedXxx} comma-separated lists +
 * {@code UseRuleForAllXxxExceptListed} inversion flags + Min/Max ranges
 * + flight-category booleans + threshold-text controls); the new V4
 * destination collapses them all into a single {@code filter_config
 * jsonb}. Per the V4 schema {@code COMMENT} the jsonb is shaped per
 * {@code filter_type_id} discriminator; per-discriminator allow-list
 * validation lives at S-064 (the rule engine reads only the keys
 * relevant to its filter type). Mapper-side: emit the union of all
 * predicate columns as a flat object, preserving the
 * {@code {"useAllExcept": bool, "matched": [...]}} paired shape so
 * inversion semantics survive. Jackson default-typing globally disabled
 * (V4 {@code filter_config} COMMENT) — A03 mitigation; mapper uses only
 * the {@code ObjectMapper} + concrete node types, never polymorphic
 * deserialisation.
 *
 * <p>The {@code MatchedXxx} legacy columns are comma-separated text
 * lists. Split on comma, trim each element, drop blanks — surface as
 * a JSON string array. Empty / NULL legacy → empty array.
 *
 * <p>{@code (operating_club_id, sort_indicator)} UNIQUE partial
 * collisions: producer-side re-number on detection +
 * {@code ACCOUNTING_RULE_SORT_RENUMBERED} warning. Mapper passes through.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted}.
 */
public final class AccountingRuleFilterMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String FILTER_TYPE_ID = "filter_type_id";
    static final String ACCOUNTING_UNIT_TYPE_ID = "accounting_unit_type_id";
    static final String RULE_FILTER_NAME = "rule_filter_name";
    static final String DESCRIPTION = "description";
    static final String IS_ACTIVE = "is_active";
    static final String SORT_INDICATOR = "sort_indicator";
    static final String STOP_RULE_ENGINE_WHEN_APPLIED = "stop_rule_engine_when_applied";
    static final String IS_CHARGED_TO_CLUB_INTERNAL = "is_charged_to_club_internal";
    static final String ARTICLE_TARGET = "article_target";
    static final String RECIPIENT_TARGET = "recipient_target";
    static final String FILTER_CONFIG = "filter_config";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, FILTER_TYPE_ID, ACCOUNTING_UNIT_TYPE_ID,
            RULE_FILTER_NAME, DESCRIPTION, IS_ACTIVE, SORT_INDICATOR,
            STOP_RULE_ENGINE_WHEN_APPLIED, IS_CHARGED_TO_CLUB_INTERNAL,
            ARTICLE_TARGET, RECIPIENT_TARGET, FILTER_CONFIG,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    /**
     * Default Jackson {@link ObjectMapper} — concrete node types only;
     * default-typing remains disabled (V4 schema A03 mitigation). Lives
     * as a static singleton: ObjectMapper is thread-safe after
     * configuration, and the mapper hot path requires zero allocation
     * beyond Jackson + JDBC inherent (S-188 budget).
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Legacy boolean-pair predicates: {@code UseRuleForAllXxxExceptListed}
     * inversion flag + {@code MatchedXxx} comma-separated list →
     * {@code {"useAllExcept": bool, "matched": [...]}} jsonb shape.
     */
    private static final BooleanPair[] BOOLEAN_PAIRS = {
            new BooleanPair("UseRuleForAllAircraftsExceptListed",
                    "MatchedAircraftImmatriculations", "aircraftImmatriculations"),
            new BooleanPair("UseRuleForAllStartTypesExceptListed",
                    "MatchedStartTypes", "startTypes"),
            new BooleanPair("UseRuleForAllFlightTypesExceptListed",
                    "MatchedFlightTypeCodes", "flightTypeCodes"),
            new BooleanPair("UseRuleForAllStartLocationsExceptListed",
                    "MatchedStartLocations", "startLocations"),
            new BooleanPair("UseRuleForAllLdgLocationsExceptListed",
                    "MatchedLdgLocations", "ldgLocations"),
            new BooleanPair("UseRuleForAllClubMemberNumbersExceptListed",
                    "MatchedClubMemberNumbers", "clubMemberNumbers"),
            new BooleanPair("UseRuleForAllFlightCrewTypesExceptListed",
                    "MatchedFlightCrewTypes", "flightCrewTypes"),
            new BooleanPair("UseRuleForAllAircraftsOnHomebaseExceptListed",
                    "MatchedAircraftsHomebase", "aircraftHomebases"),
            new BooleanPair("UseRuleForAllMemberStatesExceptListed",
                    "MatchedMemberStates", "memberStates"),
            new BooleanPair("UseRuleForAllPersonCategoriesExceptListed",
                    "MatchedPersonCategories", "personCategories")
    };

    @Override
    public EntityType entityType() {
        return EntityType.ACCOUNTING_RULE_FILTER;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        // filter_type_id + accounting_unit_type_id resolve via V4-seeded
        // legacy_int_id (entities outside EntityType — same pattern as
        // FlightMapper's process_state_id / flight_cost_balance_type_id).
        return List.of(EntityType.CLUB);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("AccountingRuleFilterId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("ClubId"));
        target.writeStringField(FILTER_TYPE_ID,
                Coercions.legacyIntIdToUuidString(source.getInt("AccountingRuleFilterTypeId")));
        Coercions.writeOptionalString(target, ACCOUNTING_UNIT_TYPE_ID,
                Coercions.optionalLegacyIntIdAsUuidString(source, "AccountingUnitTypeId"));
        target.writeStringField(RULE_FILTER_NAME, source.getString("RuleFilterName"));
        Coercions.writeOptionalString(target, DESCRIPTION, source.getString("Description"));
        target.writeBooleanField(IS_ACTIVE, source.getBoolean("IsActive"));
        target.writeNumberField(SORT_INDICATOR, source.getInt("SortIndicator"));
        target.writeBooleanField(STOP_RULE_ENGINE_WHEN_APPLIED,
                source.getBoolean("StopRuleEngineWhenRuleApplied"));
        target.writeBooleanField(IS_CHARGED_TO_CLUB_INTERNAL,
                source.getBoolean("IsChargedToClubInternal"));
        Coercions.writeOptionalString(target, ARTICLE_TARGET, source.getString("ArticleTarget"));
        Coercions.writeOptionalString(target, RECIPIENT_TARGET,
                source.getString("RecipientTarget"));
        target.writeFieldName(FILTER_CONFIG);
        // writeRawValue keeps the mapper independent of the JsonGenerator's
        // ObjectCodec wiring (the abstract contract test creates raw
        // generators without one). Serializes the predicate fold once via
        // the static ObjectMapper.
        target.writeRawValue(JSON.writeValueAsString(buildFilterConfig(source)));
        Coercions.writeRequiredTimestamp(target, CREATED_ON, source.getTimestamp("CreatedOn"));
        target.writeStringField(CREATED_BY_USER_ID, source.getString("CreatedByUserId"));
        Coercions.writeOptionalTimestamp(target, MODIFIED_ON, source.getTimestamp("ModifiedOn"));
        Coercions.writeOptionalString(target, MODIFIED_BY_USER_ID,
                source.getString("ModifiedByUserId"));
        Coercions.writeOptionalTimestamp(target, DELETED_ON, source.getTimestamp("DeletedOn"));
        Coercions.writeOptionalString(target, DELETED_BY_USER_ID,
                source.getString("DeletedByUserId"));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(OPERATING_CLUB_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(FILTER_TYPE_ID).asText()));
        target.setObject(position++, Coercions.readUuidOrNull(source, ACCOUNTING_UNIT_TYPE_ID));
        target.setString(position++, source.get(RULE_FILTER_NAME).asText());
        target.setString(position++, Coercions.readStringOrNull(source, DESCRIPTION));
        target.setObject(position++, source.get(IS_ACTIVE).asBoolean());
        target.setInt(position++, source.get(SORT_INDICATOR).intValue());
        target.setObject(position++, source.get(STOP_RULE_ENGINE_WHEN_APPLIED).asBoolean());
        target.setObject(position++, source.get(IS_CHARGED_TO_CLUB_INTERNAL).asBoolean());
        target.setString(position++, Coercions.readStringOrNull(source, ARTICLE_TARGET));
        target.setString(position++, Coercions.readStringOrNull(source, RECIPIENT_TARGET));
        // jsonb bind via Types.OTHER routes the String through pg-jdbc's
        // jsonb path. Explicit writeValueAsString rather than JsonNode.toString
        // makes the serializer contract reviewable at the call site.
        try {
            target.setObject(position++,
                    JSON.writeValueAsString(source.get(FILTER_CONFIG)), Types.OTHER);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreachable) {
            throw new SQLException(
                    "filter_config serialisation failed — JsonNode subtree is always serialisable",
                    unreachable);
        }
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode buildFilterConfig(
            ResultSet source) throws SQLException {
        com.fasterxml.jackson.databind.node.ObjectNode config = JSON.createObjectNode();

        config.put("isRuleForGliderFlights", source.getBoolean("IsRuleForGliderFlights"));
        config.put("isRuleForTowingFlights", source.getBoolean("IsRuleForTowingFlights"));
        config.put("isRuleForMotorFlights", source.getBoolean("IsRuleForMotorFlights"));

        config.put("noLandingTaxForGlider", source.getBoolean("NoLandingTaxForGlider"));
        config.put("noLandingTaxForTowingAircraft",
                source.getBoolean("NoLandingTaxForTowingAircraft"));
        config.put("noLandingTaxForAircraft", source.getBoolean("NoLandingTaxForAircraft"));

        config.put("includeFlightTypeName", source.getBoolean("IncludeFlightTypeName"));
        config.put("extendMatchingFlightTypeCodesToGliderAndTowFlight",
                source.getBoolean("ExtendMatchingFlightTypeCodesToGliderAndTowFlight"));
        config.put("includeThresholdText", source.getBoolean("IncludeThresholdText"));

        putOptionalString(config, "thresholdText", source.getString("ThresholdText"));
        putOptionalInt(config, "minFlightTimeInSecondsMatchingValue",
                source.getObject("MinFlightTimeInSecondsMatchingValue", Integer.class));
        putOptionalInt(config, "maxFlightTimeInSecondsMatchingValue",
                source.getObject("MaxFlightTimeInSecondsMatchingValue", Integer.class));
        putOptionalInt(config, "minEngineTimeInSecondsMatchingValue",
                source.getObject("MinEngineTimeInSecondsMatchingValue", Integer.class));
        putOptionalInt(config, "maxEngineTimeInSecondsMatchingValue",
                source.getObject("MaxEngineTimeInSecondsMatchingValue", Integer.class));

        for (BooleanPair pair : BOOLEAN_PAIRS) {
            com.fasterxml.jackson.databind.node.ObjectNode pairNode = config.putObject(pair.jsonKey);
            pairNode.put("useAllExcept", source.getBoolean(pair.useAllExceptColumn));
            com.fasterxml.jackson.databind.node.ArrayNode matched = pairNode.putArray("matched");
            String rawList = source.getString(pair.matchedColumn);
            if (rawList != null) {
                for (String element : rawList.split(",")) {
                    String trimmed = element.trim();
                    if (!trimmed.isEmpty()) {
                        matched.add(trimmed);
                    }
                }
            }
        }

        return config;
    }

    private static void putOptionalString(
            com.fasterxml.jackson.databind.node.ObjectNode target,
            String fieldName, @Nullable String value) {
        if (value == null) {
            target.putNull(fieldName);
        } else {
            target.put(fieldName, value);
        }
    }

    private static void putOptionalInt(
            com.fasterxml.jackson.databind.node.ObjectNode target,
            String fieldName, @Nullable Integer value) {
        if (value == null) {
            target.putNull(fieldName);
        } else {
            target.put(fieldName, value.intValue());
        }
    }

    /**
     * Legacy boolean-pair predicate: the inversion flag + the
     * comma-separated value list. Surfaces in {@code filter_config}
     * jsonb as a nested object preserving both halves.
     */
    private record BooleanPair(
            String useAllExceptColumn,
            String matchedColumn,
            String jsonKey) { }
}

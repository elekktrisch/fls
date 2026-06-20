package ch.alpenflight.migration.bundle.accounting;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ForeignKeyColumn;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import ch.alpenflight.migration.bundle.ReferenceLookup;
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
 * <p><strong>The {@code MatchedXxx} legacy columns are JSON arrays</strong>
 * (T-10 reconciliation against the real legacy schema). The legacy C#
 * {@code AccountingRuleFilterMappingExtensions} serialises every match-list
 * via {@code JsonConvert.SerializeObject(List<…>)} ({@code :331-340}) and
 * deserialises via {@code JsonConvert.DeserializeObject<List<…>>}
 * ({@code :240-250}) — so the {@code nvarchar(max)} columns hold JSON arrays
 * ({@code ["HB-3170","HB-1234"]}, {@code [1,2]}, {@code ["<guid>"]}), NOT
 * comma-separated text. This mapper parses each column as a JSON array and
 * normalises every element to a trimmed string → {@code matched: String[]}
 * (ints/Guids surface as their string form, matching {@link
 * ch.alpenflight.accounting.domain.FilterConfig.MatchList}'s uniform
 * {@code String[]}). Empty array / NULL / blank legacy → empty array. (The
 * prior {@code rawList.split(",")} corrupted a JSON array into a single
 * element {@code ["[\"HB-3170\"]"]} — the classic producer-SELECT format
 * gap the synth bundle never caught.)
 *
 * <p><strong>{@code ArticleTarget} / {@code RecipientTarget} are JSON
 * blobs, not scalars</strong> (T-10). Legacy {@code ArticleTarget}
 * {@code nvarchar(max)} = {@code {ArticleNumber, DeliveryLineText}}
 * ({@code ArticleTargetDetails}); {@code RecipientTarget} =
 * {@code {PersonClubMemberNumber, RecipientName, …}}
 * ({@code RecipientDetails}). The producer SELECT extracts the scalars via
 * MSSQL {@code JSON_VALUE}: {@code ArticleTarget.ArticleNumber} →
 * {@code article_target VARCHAR(50)}, {@code RecipientTarget
 * .PersonClubMemberNumber} → {@code recipient_target}; the descriptive
 * {@code DeliveryLineText} + {@code RecipientName} ride
 * {@code filter_config} (so the form round-trips them per the AC "reload
 * round-trips every field"). Reading the whole blob into the
 * {@code VARCHAR(50)} columns would overflow / dump garbage.
 *
 * <p><strong>{@code filter_type_id} + {@code accounting_unit_type_id}
 * resolve via {@link #referenceLookups()}</strong> (T-10): both carry the
 * synthetic {@code new UUID(0, legacyIntId)} and the ingest joins
 * {@code t_accounting_rule_filter_type} / {@code t_accounting_unit_type}
 * on {@code legacy_int_id} (V4 seed + V42 added types 5/55).
 *
 * <p>{@code (operating_club_id, sort_indicator)} UNIQUE partial
 * collisions (ux_arf_club_sort_partial): the legacy {@code SortIndicator}
 * is dup-prone {@code int NULL} (no such legacy constraint). The producer
 * SELECT RENUMBERS on collision via {@code ROW_NUMBER() OVER (PARTITION BY
 * ClubId ORDER BY SortIndicator, CreatedOn)} over the soft-delete-LIVE rows
 * the partial index covers ({@code WHERE IsDeleted = 0}), so every exported
 * row carries a DISTINCT {@code sort_indicator} for its club —
 * {@code ACCOUNTING_RULE_SORT_RENUMBERED}. Mapper passes the renumbered
 * value through. Locked by {@code AccountingRuleFilterProducerDedupeIT}.
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
    public List<ForeignKeyColumn> foreignKeyColumns() {
        // operating_club_id → CLUB is off-convention: the resolver's <target>_id
        // default would look for a club_id column (which does not exist on
        // t_accounting_rule_filter), leave operating_club_id as the legacy GUID,
        // and FK-violate fk_accounting_rule_filter_operating_club_id (23503) at
        // INSERT. Declared explicitly (the FlightMapper idiom for its @TenantId).
        return List.of(new ForeignKeyColumn(OPERATING_CLUB_ID, EntityType.CLUB));
    }

    @Override
    public List<ReferenceLookup> referenceLookups() {
        // filter_type_id + accounting_unit_type_id carry the synthetic
        // new UUID(0, legacyIntId) (writeNdjson uses legacyIntIdToUuidString /
        // optionalLegacyIntIdAsUuidString) for V4/V42-seeded reference rows
        // OUTSIDE EntityType — resolved structurally against each seed table's
        // legacy_int_id (ux_arft_legacy_int_id / ux_aut_legacy_int_id point
        // lookups), exactly like FlightMapper's process_state_id. Without these
        // the ingest leaves the synthetic UUID verbatim and the row FK-violates
        // fk_arf_filter_type_id at INSERT (T-09 finding — load-bearing for ALL
        // filter types). filter_type_id is required (NOT NULL); a NULL
        // accounting_unit_type_id is skipped by the resolver.
        return List.of(
                new ReferenceLookup(FILTER_TYPE_ID, "t_accounting_rule_filter_type"),
                new ReferenceLookup(ACCOUNTING_UNIT_TYPE_ID, "t_accounting_unit_type"));
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
        Coercions.writeRequiredTimestampCoalescing(
                target, MODIFIED_ON, source.getTimestamp("ModifiedOn"),
                source.getTimestamp("CreatedOn"));
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
                clampToIntRange(source.getObject(
                        "MinFlightTimeInSecondsMatchingValue", Long.class)));
        putOptionalInt(config, "maxFlightTimeInSecondsMatchingValue",
                clampToIntRange(source.getObject(
                        "MaxFlightTimeInSecondsMatchingValue", Long.class)));
        putOptionalInt(config, "minEngineTimeInSecondsMatchingValue",
                clampToIntRange(source.getObject(
                        "MinEngineTimeInSecondsMatchingValue", Long.class)));
        putOptionalInt(config, "maxEngineTimeInSecondsMatchingValue",
                clampToIntRange(source.getObject(
                        "MaxEngineTimeInSecondsMatchingValue", Long.class)));

        // The descriptive text fields that travel with the article/recipient
        // targets (T-03 added them to FilterConfig; T-10 emits them). The
        // producer SELECT extracts them from the legacy JSON blobs via
        // JSON_VALUE(ArticleTarget,'$.DeliveryLineText') AS DeliveryLineText /
        // JSON_VALUE(RecipientTarget,'$.RecipientName') AS RecipientName. The
        // bare scalars (ArticleNumber / member-number) live in the
        // article_target / recipient_target columns, NOT here.
        putOptionalString(config, "deliveryLineText", source.getString("DeliveryLineText"));
        putOptionalString(config, "recipientName", source.getString("RecipientName"));

        for (BooleanPair pair : BOOLEAN_PAIRS) {
            com.fasterxml.jackson.databind.node.ObjectNode pairNode = config.putObject(pair.jsonKey);
            pairNode.put("useAllExcept", source.getBoolean(pair.useAllExceptColumn));
            com.fasterxml.jackson.databind.node.ArrayNode matched = pairNode.putArray("matched");
            appendJsonArrayElements(matched, source.getString(pair.matchedColumn));
        }

        return config;
    }

    /**
     * Parse a legacy {@code MatchedXxx} column — a {@code JsonConvert
     * .SerializeObject(List<…>)} JSON array (legacy C#
     * {@code AccountingRuleFilterMappingExtensions:331-340}) — and append every
     * element as a trimmed string to {@code matched}. Ints / Guids surface as
     * their string form (uniform {@code String[]} per {@code FilterConfig
     * .MatchList}). NULL / blank / {@code "null"} / empty-array legacy → no
     * elements appended (empty matched array, preserving the inversion flag).
     * Blank elements are dropped.
     *
     * <p>A column that is NOT valid JSON (legacy dirty data) is treated as a
     * single scalar value — defensive, never throws mid-stream: a malformed
     * predicate must not abort a multi-million-row export.
     */
    private static void appendJsonArrayElements(
            com.fasterxml.jackson.databind.node.ArrayNode matched, @Nullable String rawJson) {
        if (rawJson == null) {
            return;
        }
        String trimmed = rawJson.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return;
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(trimmed);
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            // Legacy dirty data: not a JSON array — keep the raw value as one
            // trimmed element rather than aborting the export.
            if (!trimmed.isEmpty()) {
                matched.add(trimmed);
            }
            return;
        }
        if (parsed.isArray()) {
            for (JsonNode element : parsed) {
                if (element.isNull()) {
                    continue;
                }
                String value = element.asText().trim();
                if (!value.isEmpty()) {
                    matched.add(value);
                }
            }
        } else if (!parsed.isNull()) {
            // A bare scalar (legacy single-value column not wrapped in an array).
            String value = parsed.asText().trim();
            if (!value.isEmpty()) {
                matched.add(value);
            }
        }
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
     * The legacy {@code Min/MaxFlightTimeInSecondsMatchingValue} +
     * {@code Min/MaxEngineTimeInSecondsMatchingValue} columns are
     * {@code bigint NULL} (DBUpdate v1.9.14) — read as {@link Long}, not
     * {@link Integer}: {@code ResultSet.getObject(col, Integer.class)} throws on
     * a {@code bigint} column (pgjdbc {@code conversion … from int8 not
     * supported}; the producer SELECT runs on PG 17 / MSSQL). The "unbounded
     * max" sentinel the legacy alter writes is {@code Long.MAX_VALUE
     * (9223372036854775807)}, far beyond the engine's {@code int}-typed
     * {@code FilterConfig} window; clamp to the {@code int} range so the value
     * deserialises (the clamped {@code Integer.MAX_VALUE} is still unbounded for
     * any real flight duration, preserving the {@code Between max-inclusive}
     * semantics).
     */
    private static @Nullable Integer clampToIntRange(@Nullable Long value) {
        if (value == null) {
            return null;
        }
        long clamped = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
        return (int) clamped;
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

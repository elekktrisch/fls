package ch.alpenflight.accounting.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Typed value object for the {@code t_accounting_rule_filter.filter_config}
 * jsonb column — the variable predicate bag whose shape varies per
 * {@code filter_type_id}. Persisted as a typed record (not a String) via
 * Hibernate's {@code @JdbcTypeCode(SqlTypes.JSON)} on the aggregate;
 * Hibernate's auto-registered Jackson {@code FormatMapper} serialises the
 * record reflectively by component name, so this class carries NO Jackson
 * annotations (ADR 0023 — {@code domain/} stays Jackson-free).
 *
 * <p><strong>Migration parity (load-bearing).</strong> The component names
 * here MUST match the keys emitted by the migration mapper's
 * {@code AccountingRuleFilterMapper.buildFilterConfig} so a migrated legacy
 * row deserialises into this record without loss. The mapper folds the legacy
 * 30+ {@code MatchedXxx} / {@code UseRuleForAllXxxExceptListed} predicate
 * columns into the 10 {@link MatchList} pairs + the flat flags/ranges/threshold
 * fields below. The match-list {@code matched} arrays are uniformly
 * {@code String[]} even where the legacy ids were ints/Guids (the mapper
 * serialises every element as a trimmed string).
 *
 * <p>{@code deliveryLineText} + {@code recipientName} are the descriptive text
 * fields that travel with the rule's article/recipient targets (the bare
 * ArticleNumber / member-number strings live in the
 * {@code article_target} / {@code recipient_target} columns). They are NOT yet
 * emitted by the migration mapper — T-09 adds them to {@code buildFilterConfig}
 * so migrated rows round-trip them (until then a migrated row deserialises them
 * as {@code null}, which is the correct "unknown" value).
 *
 * <p>This record validates only its own structural integrity (no null
 * {@link MatchList} pairs); per-{@code filter_type} field requirements are the
 * rules engine's concern (J-9, S-064) and the aggregate's create/update
 * guards, not this value object's.
 */
public record FilterConfig(
        // flight-kind scope flags
        boolean isRuleForGliderFlights,
        boolean isRuleForTowingFlights,
        boolean isRuleForMotorFlights,
        // no-landing-tax flags
        boolean noLandingTaxForGlider,
        boolean noLandingTaxForTowingAircraft,
        boolean noLandingTaxForAircraft,
        // delivery-line presentation flags
        boolean includeFlightTypeName,
        boolean extendMatchingFlightTypeCodesToGliderAndTowFlight,
        boolean includeThresholdText,
        // nullable scalars
        @Nullable String thresholdText,
        @Nullable Integer minFlightTimeInSecondsMatchingValue,
        @Nullable Integer maxFlightTimeInSecondsMatchingValue,
        @Nullable Integer minEngineTimeInSecondsMatchingValue,
        @Nullable Integer maxEngineTimeInSecondsMatchingValue,
        // 10 predicate match-list pairs (each {useAllExcept, matched[]})
        MatchList aircraftImmatriculations,
        MatchList startTypes,
        MatchList flightTypeCodes,
        MatchList startLocations,
        MatchList ldgLocations,
        MatchList clubMemberNumbers,
        MatchList flightCrewTypes,
        MatchList aircraftHomebases,
        MatchList memberStates,
        MatchList personCategories,
        // descriptive text travelling with the targets (T-09 mapper addition)
        @Nullable String deliveryLineText,
        @Nullable String recipientName) {

    public FilterConfig {
        aircraftImmatriculations = orEmpty(aircraftImmatriculations);
        startTypes = orEmpty(startTypes);
        flightTypeCodes = orEmpty(flightTypeCodes);
        startLocations = orEmpty(startLocations);
        ldgLocations = orEmpty(ldgLocations);
        clubMemberNumbers = orEmpty(clubMemberNumbers);
        flightCrewTypes = orEmpty(flightCrewTypes);
        aircraftHomebases = orEmpty(aircraftHomebases);
        memberStates = orEmpty(memberStates);
        personCategories = orEmpty(personCategories);
    }

    /**
     * An empty config — all flags false, all scalars null, all 10 match-lists
     * empty with the legacy default {@code useAllExcept = true}. Matches the
     * V4 column default {@code '{}'::jsonb} (absent keys deserialise to the
     * record's zero/null/empty defaults) and the legacy form's initial state.
     */
    public static FilterConfig empty() {
        return new FilterConfig(
                false, false, false,
                false, false, false,
                false, false, false,
                null, null, null, null, null,
                MatchList.empty(), MatchList.empty(), MatchList.empty(),
                MatchList.empty(), MatchList.empty(), MatchList.empty(),
                MatchList.empty(), MatchList.empty(), MatchList.empty(),
                MatchList.empty(),
                null, null);
    }

    private static MatchList orEmpty(@Nullable MatchList value) {
        return value == null ? MatchList.empty() : value;
    }

    /**
     * One predicate list with its include/exclude inversion toggle. The legacy
     * {@code UseRuleForAllXxxExceptListed} flag → {@link #useAllExcept}: when
     * {@code true} the rule applies to ALL values EXCEPT those listed; when
     * {@code false} it applies ONLY to the listed values. Legacy default is
     * {@code true} (see {@link #empty()}). {@code matched} is the trimmed,
     * blank-free value list (always {@code String[]} per the mapper).
     */
    public record MatchList(boolean useAllExcept, List<String> matched) {

        public MatchList {
            matched = matched == null ? List.of() : List.copyOf(matched);
        }

        /** Empty list with the legacy default {@code useAllExcept = true}. */
        public static MatchList empty() {
            return new MatchList(true, List.of());
        }
    }
}

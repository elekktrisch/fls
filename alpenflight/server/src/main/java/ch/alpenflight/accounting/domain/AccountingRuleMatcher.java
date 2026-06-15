package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.FilterConfig.MatchList;
import ch.alpenflight.accounting.domain.MatchableFlight.MatchableCrew;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The rules-engine matching predicate — decides whether one
 * {@link FilterConfig} applies to one {@link MatchableFlight}. The pure-domain
 * port of the legacy {@code BaseAccountingRule} condition set: every facet is
 * AND-ed (a filter matches iff ALL its conditions hold), and each facet follows
 * the same {@code useAllExcept × empty/non-empty} matrix as the legacy
 * {@code Initialize*Conditions} methods.
 *
 * <p>The engine stages (T-07+) hold an {@link AccountingRuleFilter} and call
 * {@code matches(flight, filter.filterConfig())} per filter; a {@code true}
 * means the stage emits / acts on its line for that flight.
 *
 * <p>Ported line-by-line from
 * {@code flsserver/src/FLS.Server.Service/Accounting/Rules/BaseAccountingRule.cs}
 * — NOT rewritten from understanding (customer invoices depend on the current
 * behavior, bit-exact parity is the J-9 contract). Person-category filtering is
 * a no-op here because it is commented out in the legacy source (lines 177-201).
 */
public final class AccountingRuleMatcher {

    public boolean matches(MatchableFlight flight, FilterConfig filter) {
        return flightAircraftTypeMatches(flight, filter)
                && aircraftMatches(flight, filter)
                && startTypeMatches(flight, filter)
                && flightTypeMatches(flight, filter)
                && startLocationMatches(flight, filter)
                && ldgLocationMatches(flight, filter)
                && crewMatches(flight, filter)
                && aircraftHomebaseMatches(flight, filter);
    }

    private boolean flightAircraftTypeMatches(MatchableFlight flight, FilterConfig filter) {
        int ruleValue = (filter.isRuleForGliderFlights() ? 1 : 0)
                + (filter.isRuleForTowingFlights() ? 2 : 0)
                + (filter.isRuleForMotorFlights() ? 4 : 0);
        int flightBit = flight.flightAircraftTypeBit();
        return (flightBit & ruleValue) == flightBit;
    }

    private boolean aircraftMatches(MatchableFlight flight, FilterConfig filter) {
        return facetMatches(filter.aircraftImmatriculations(), flight.immatriculation(), true);
    }

    private boolean startTypeMatches(MatchableFlight flight, FilterConfig filter) {
        return facetMatches(filter.startTypes(), flight.startTypeId(), false);
    }

    private boolean startLocationMatches(MatchableFlight flight, FilterConfig filter) {
        if (flight.startLocationIcao() == null) {
            return true; // legacy: no start location -> warn only, add no condition.
        }
        return facetMatches(filter.startLocations(), flight.startLocationIcao(), true);
    }

    private boolean ldgLocationMatches(MatchableFlight flight, FilterConfig filter) {
        if (flight.ldgLocationIcao() == null) {
            return true; // legacy: no landing location -> warn only, add no condition.
        }
        return facetMatches(filter.ldgLocations(), flight.ldgLocationIcao(), true);
    }

    private boolean flightTypeMatches(MatchableFlight flight, FilterConfig filter) {
        MatchList codes = filter.flightTypeCodes();
        if (codes.useAllExcept() && codes.matched().isEmpty()) {
            return true; // useAllExcept + empty -> no condition.
        }
        boolean own = containsCode(codes, flight.flightTypeCode());
        boolean extended = own;
        if (filter.extendMatchingFlightTypeCodesToGliderAndTowFlight()) {
            for (String towedCode : flight.towedFlightTypeCodes()) {
                extended = extended || containsCode(codes, towedCode);
            }
            extended = extended || containsCode(codes, flight.towFlightTypeCode());
        }
        return codes.useAllExcept() ? !extended : extended;
    }

    private boolean aircraftHomebaseMatches(MatchableFlight flight, FilterConfig filter) {
        MatchList homebases = filter.aircraftHomebases();
        if (!flight.aircraftPresent()) {
            return true; // legacy: no aircraft -> warn only, add no condition.
        }
        boolean hasHomebase = flight.aircraftHomebase() != null;
        if (homebases.useAllExcept()) {
            if (homebases.matched().isEmpty()) {
                return true; // useAllExcept + empty -> no condition.
            }
            // No homebase under an exclude list never excludes (Equals(false, false)).
            return !hasHomebase || !contains(homebases.matched(), flight.aircraftHomebase(), false);
        }
        // Include list: no homebase is forced-false (Equals(false, true)).
        return hasHomebase && contains(homebases.matched(), flight.aircraftHomebase(), false);
    }

    private boolean crewMatches(MatchableFlight flight, FilterConfig filter) {
        if (flight.crew().isEmpty()) {
            return false; // legacy: no crew -> forced-false condition (rule never matches).
        }
        List<MatchableCrew> selected = selectByCrewType(flight.crew(), filter.flightCrewTypes());
        return crewFacetMatches(filter.clubMemberNumbers(), selected, MatchableCrew::memberNumber, "member-number")
                && crewFacetMatches(filter.memberStates(), selected, MatchableCrew::memberStateId, "member-state");
    }

    /**
     * The legacy crew-type pre-filter ({@code flightCrewTypeSelection}): the
     * crew members whose member-number / member-state the rule then inspects.
     * It is NOT a standalone match condition — it narrows the crew set.
     */
    private List<MatchableCrew> selectByCrewType(List<MatchableCrew> crew, MatchList crewTypes) {
        if (crewTypes.useAllExcept()) {
            if (crewTypes.matched().isEmpty()) {
                return crew; // all crew types.
            }
            Set<String> excluded = upper(crewTypes.matched());
            return crew.stream()
                    .filter(c -> !excluded.contains(c.flightCrewTypeId().toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        Set<String> included = upper(crewTypes.matched());
        return crew.stream()
                .filter(c -> included.contains(c.flightCrewTypeId().toUpperCase(Locale.ROOT)))
                .collect(Collectors.toList());
    }

    private List<String> resolve(List<MatchableCrew> crew,
                                 java.util.function.Function<MatchableCrew, @Nullable String> facet,
                                 String facetName) {
        return crew.stream()
                .map(c -> requireResolved(facet.apply(c), facetName))
                .collect(Collectors.toList());
    }

    private static String requireResolved(@Nullable String value, String facet) {
        if (value == null) {
            throw new MissingPersonClubException(
                    "Crew person has no PersonClub for the delivery's club; cannot resolve " + facet);
        }
        return value;
    }

    /**
     * The crew facet uses {@code IntersectAny} (does any selected crew value
     * appear in the matched list?) rather than {@code Contains} of a single
     * value — the {@code useAllExcept × empty/non-empty} matrix is otherwise
     * identical to {@link #facetMatches}.
     *
     * <p>Crew values are resolved (and may throw {@link MissingPersonClubException})
     * only inside the branches that add a condition — exactly as legacy resolves
     * {@code PersonClubs.First(...)} only when the matching branch runs. The
     * useAllExcept + empty case adds no condition and never touches the values.
     */
    private boolean crewFacetMatches(MatchList list,
                                     List<MatchableCrew> selected,
                                     java.util.function.Function<MatchableCrew, @Nullable String> facet,
                                     String facetName) {
        if (list.useAllExcept()) {
            if (list.matched().isEmpty()) {
                return true; // useAllExcept + empty -> no condition.
            }
            return !intersectsAny(list.matched(), resolve(selected, facet, facetName));
        }
        return intersectsAny(list.matched(), resolve(selected, facet, facetName));
    }

    /**
     * One {@code Contains}-style facet's matrix:
     * <ul>
     *   <li>useAllExcept + non-empty -&gt; EXCLUDE: {@code !matched.contains(value)}</li>
     *   <li>useAllExcept + empty -&gt; no condition (matches all)</li>
     *   <li>!useAllExcept -&gt; INCLUDE: {@code matched.contains(value)}
     *       (empty list never matches — the legacy forced-false)</li>
     * </ul>
     */
    private boolean facetMatches(MatchList list, @Nullable String flightValue, boolean caseInsensitive) {
        if (list.useAllExcept()) {
            if (list.matched().isEmpty()) {
                return true;
            }
            return !contains(list.matched(), flightValue, caseInsensitive);
        }
        return contains(list.matched(), flightValue, caseInsensitive);
    }

    private boolean containsCode(MatchList codes, @Nullable String value) {
        return contains(codes.matched(), value, true);
    }

    private static boolean contains(List<String> matched, @Nullable String value, boolean caseInsensitive) {
        if (value == null) {
            return false;
        }
        if (!caseInsensitive) {
            return matched.contains(value);
        }
        String upper = value.toUpperCase(Locale.ROOT);
        for (String candidate : matched) {
            if (candidate != null && candidate.toUpperCase(Locale.ROOT).equals(upper)) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersectsAny(List<String> matched, List<String> crewValues) {
        Set<String> matchedSet = Set.copyOf(matched);
        for (String crewValue : crewValues) {
            if (matchedSet.contains(crewValue)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> upper(List<String> values) {
        return values.stream().map(v -> v.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}

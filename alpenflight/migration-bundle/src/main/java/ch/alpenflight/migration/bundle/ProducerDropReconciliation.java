package ch.alpenflight.migration.bundle;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Folds producer-side row drops into the parity row-count equality. When the
 * producer omits a row it cannot port (an Aircraft with no resolvable managing
 * Club; a reservation with no pilot), the legacy table still carries that row —
 * so the expected new-stack count is {@code legacyCount − drops}, grouped per
 * {@code (Club, entity)}.
 *
 * <p>Only the per-row drop codes fold in. {@code ARTICLE_DUPLICATE_NUMBER} is a
 * whole-bundle reject (Swiss OR Art. 957a — silent dedupe would rewrite legal
 * records), verified by the negative-path reject cases, never reconciled away
 * here.
 */
public final class ProducerDropReconciliation {

    public static final String AIRCRAFT_NO_MANAGING_CLUB = "AIRCRAFT_NO_MANAGING_CLUB";
    public static final String RESERVATION_NO_PILOT = "RESERVATION_NO_PILOT";

    /**
     * A legacy PlanningDays row dropped by the producer-side dedupe-keep-first
     * (J-6 T-11b): the real legacy table carries duplicate
     * {@code (ClubId, Day, LocationId)} rows that have no legacy UNIQUE
     * constraint, but the new stack's {@code ux_pln_club_date_loc} partial
     * unique (V4) forbids them. PLANNING_DAY is NOT fan-out, so two dups resolve
     * to the same club + own-club Location replica and the 2nd INSERT would 23505.
     * The {@code PLANNING_DAY} producer SELECT keeps the deterministically-first
     * row per key ({@code ORDER BY CreatedOn, PlanningDayId}) and drops the rest;
     * each dropped row is a {@code PLANNING_DAY_DUPLICATE} so the count reduction
     * is visible and the parity row-count equality reconciles (legacy − drops).
     * Unlike {@code ARTICLE_DUPLICATE_NUMBER}, this is a per-row drop, not a
     * whole-bundle reject — the keep-first survivor IS migrated.
     */
    public static final String PLANNING_DAY_DUPLICATE = "PLANNING_DAY_DUPLICATE";

    /**
     * A legacy PlanningDayAssignments row dropped by the producer-side
     * POST-REMAP composite dedupe-keep-first (J-7 T-17): the
     * {@code PLANNING_DAY_ASSIGNMENT} producer REMAPS each assignment's
     * planning_day_id onto the surviving kept-first day (the J-6 T-16 23503
     * fix). When two assignments of two DUPLICATE planning days share the same
     * {@code (AssignedPersonId, AssignmentTypeId)}, the remap collapses them
     * onto the SAME surviving planning_day_id → two rows with an identical
     * {@code (planning_day_id, assigned_person_id, assignment_type_id)}, which
     * V4's {@code ux_pda_composite} UNIQUE forbids (the 2nd INSERT 23505s — the
     * §4 fanout blocker T-17 repaired). The producer SELECT keeps the
     * deterministically-first row per post-remap composite (a live row over a
     * soft-deleted one, then earliest CreatedOn, then the GUID) and drops the
     * rest; each dropped row is a {@code PLANNING_DAY_ASSIGNMENT_DUPLICATE} so
     * the count reduction is visible and the parity row-count equality
     * reconciles (legacy − drops). Like {@code PLANNING_DAY_DUPLICATE}, a
     * per-row drop, not a whole-bundle reject — the keep-first survivor IS
     * migrated.
     */
    public static final String PLANNING_DAY_ASSIGNMENT_DUPLICATE =
            "PLANNING_DAY_ASSIGNMENT_DUPLICATE";

    /** Codes that reduce the expected per-(Club, entity) new-stack row count. */
    public static final Set<String> ROW_DROP_CODES =
            Set.of(AIRCRAFT_NO_MANAGING_CLUB, RESERVATION_NO_PILOT, PLANNING_DAY_DUPLICATE,
                    PLANNING_DAY_ASSIGNMENT_DUPLICATE);

    private ProducerDropReconciliation() { }

    /**
     * Counts the row drops per {@code (entity, clubId)}. {@code clubId} is the
     * lower-cased UUID string, or the empty string when the drop carried no
     * Club — matching the diff engine's null-Club bucket so the subtraction
     * lines up key-for-key.
     */
    public static Map<ClubEntity, Long> dropCountsByClubAndEntity(
            List<ProducerDropWarning> warnings) {
        Map<ClubEntity, Long> counts = new HashMap<>();
        for (ProducerDropWarning warning : warnings) {
            if (!ROW_DROP_CODES.contains(warning.code())) {
                continue;
            }
            String clubId = warning.clubId() == null
                    ? ""
                    : warning.clubId().toString().toLowerCase(Locale.ROOT);
            ClubEntity key = new ClubEntity(warning.entityType(), clubId);
            counts.merge(key, 1L, Long::sum);
        }
        return counts;
    }

    /** Grouping key matching the diff engine's per-(entity, Club) row-count map. */
    public record ClubEntity(EntityType entity, String clubId) {
    }
}

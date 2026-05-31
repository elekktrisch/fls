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

    /** Codes that reduce the expected per-(Club, entity) new-stack row count. */
    public static final Set<String> ROW_DROP_CODES =
            Set.of(AIRCRAFT_NO_MANAGING_CLUB, RESERVATION_NO_PILOT);

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

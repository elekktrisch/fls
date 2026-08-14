package ch.alpenflight.migration.bundle;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ProducerDropReconciliation {

    public static final String AIRCRAFT_NO_MANAGING_CLUB = "AIRCRAFT_NO_MANAGING_CLUB";
    public static final String RESERVATION_NO_PILOT = "RESERVATION_NO_PILOT";

    public static final String PLANNING_DAY_DUPLICATE = "PLANNING_DAY_DUPLICATE";

    public static final String PLANNING_DAY_ASSIGNMENT_DUPLICATE =
            "PLANNING_DAY_ASSIGNMENT_DUPLICATE";

    public static final Set<String> ROW_DROP_CODES =
            Set.of(AIRCRAFT_NO_MANAGING_CLUB, RESERVATION_NO_PILOT, PLANNING_DAY_DUPLICATE,
                    PLANNING_DAY_ASSIGNMENT_DUPLICATE);

    private ProducerDropReconciliation() { }

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

    public record ClubEntity(EntityType entity, String clubId) {
    }
}

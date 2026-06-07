package ch.alpenflight.planning.application;

import ch.alpenflight.planning.application.PlanningDayDtos.PlanningDayDetail;
import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningRole;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Maps the {@link PlanningDay} aggregate to the REST detail DTO (J-6 T-04). The
 * application-layer mapper — NOT the migration-bundle {@code PlanningDayMapper}
 * (which keys the legacy MSSQL export).
 *
 * <p>The three nullable crew person-id slots are projected from the generic
 * assignment rows: the service resolves each assignment's per-club type to a
 * well-known {@link PlanningRole} and hands the resulting {@code role → personId}
 * map here. The {@code numberOfAircraftReservations} is the service-computed
 * count (legacy {@code NumberOfAircraftReservations}, never stored); the
 * {@code canMutate} flag drives both {@code canUpdateRecord} and
 * {@code canDeleteRecord} (the update + delete gate is the same admin-or-creator
 * predicate — J-6 oracle).
 */
final class PlanningDayMapper {

    private PlanningDayMapper() {}

    static PlanningDayDetail toDetail(PlanningDay day,
                                      UUID id,
                                      Map<PlanningRole, UUID> crew,
                                      long numberOfAircraftReservations,
                                      boolean canMutate) {
        return new PlanningDayDetail(
                id,
                Objects.requireNonNull(day.getOperatingClubId(),
                        "PlanningDay is missing operatingClubId (@TenantId NOT NULL)"),
                Objects.requireNonNull(day.getPlanningDate(),
                        "PlanningDay is missing planningDate"),
                LocationId.of(Objects.requireNonNull(day.getLocationId(),
                        "PlanningDay is missing locationId")),
                person(crew.get(PlanningRole.INSTRUCTOR)),
                person(crew.get(PlanningRole.TOWING_PILOT)),
                person(crew.get(PlanningRole.FLIGHT_OPERATOR)),
                day.getInfo(),
                numberOfAircraftReservations,
                canMutate,
                canMutate);
    }

    private static @Nullable PersonId person(@Nullable UUID value) {
        return PersonId.ofNullable(value);
    }
}

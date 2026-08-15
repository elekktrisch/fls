package ch.alpenflight.planning.application;

import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.PersonId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class PlanningDayDtos {

    private PlanningDayDtos() {}

    @Schema(description = "Planning-day detail / overview projection.")
    public record PlanningDayDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID operatingClubId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate planningDate,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocationId locationId,
            @Nullable PersonId instructorPersonId,
            @Nullable PersonId towingPilotPersonId,
            @Nullable PersonId flightOperatorPersonId,
            @Nullable String info,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long numberOfAircraftReservations,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canUpdateRecord,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canDeleteRecord) {}

    @Schema(description = "Payload to create a planning day.")
    public record PlanningDayCreateRequest(
            @NotNull LocalDate planningDate,
            @NotNull LocationId locationId,
            @Nullable PersonId instructorPersonId,
            @Nullable PersonId towingPilotPersonId,
            @Nullable PersonId flightOperatorPersonId,
            @Nullable @Size(max = 4000) String info) {}

    @Schema(description = "Payload to update a planning day.")
    public record PlanningDayUpdateRequest(
            @NotNull LocalDate planningDate,
            @NotNull LocationId locationId,
            @Nullable PersonId instructorPersonId,
            @Nullable PersonId towingPilotPersonId,
            @Nullable PersonId flightOperatorPersonId,
            @Nullable @Size(max = 4000) String info) {}

    @Schema(description = "Candidate (date, location) to pre-check for a duplicate planning day (non-mutating).")
    public record PlanningDayValidateRequest(
            @NotNull LocalDate planningDate,
            @NotNull LocationId locationId,
            @Nullable @Schema(description = "On an edit, the planning day's own id — excluded from the "
                    + "uniqueness probe so it does not self-conflict. Absent on a create check.")
                    UUID excludePlanningDayId) {}

    @Schema(description = "Field-level validation outcome for an inline pre-check (200; valid flag + offending field).")
    public record PlanningDayValidationResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean valid,
            @Nullable String field,
            @Nullable String message) {

        public static PlanningDayValidationResult passed() {
            return new PlanningDayValidationResult(true, null, null);
        }

        public static PlanningDayValidationResult failed(String field, String message) {
            return new PlanningDayValidationResult(false, field, message);
        }
    }

    @Schema(description = "Paged planning-day list envelope (SPA-compat page shape).")
    public record PlanningDayPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PlanningDayDetail> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageSize,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalRows) {}

    @Schema(description = "Legacy PageableSearchFilter-shaped paged-list request (Day.From filter + sorting).")
    public record PlanningDayPageRequest(
            @Nullable @Schema(description = "Column→direction map; only `planningDate: asc|desc` honoured (default asc).")
                    Map<String, String> sorting,
            @Nullable PlanningDaySearchFilter searchFilter) {}

    @Schema(description = "Basic paged-list filter — the legacy Day.From lower-bound date (J-6 scope).")
    public record PlanningDaySearchFilter(
            @Nullable @Schema(description = "Lower-bound planning date (legacy `Day.From`); default today.")
                    LocalDate from) {}

    @Schema(description = "Bulk weekday-expansion rule (inclusive range × selected weekdays at a location).")
    public record PlanningDayRuleRequest(
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            boolean everyMonday,
            boolean everyTuesday,
            boolean everyWednesday,
            boolean everyThursday,
            boolean everyFriday,
            boolean everySaturday,
            boolean everySunday,
            @NotNull LocationId locationId,
            @Nullable @Size(max = 4000) String info) {

        public Set<DayOfWeek> selectedWeekdays() {
            EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            if (everyMonday) {
                days.add(DayOfWeek.MONDAY);
            }
            if (everyTuesday) {
                days.add(DayOfWeek.TUESDAY);
            }
            if (everyWednesday) {
                days.add(DayOfWeek.WEDNESDAY);
            }
            if (everyThursday) {
                days.add(DayOfWeek.THURSDAY);
            }
            if (everyFriday) {
                days.add(DayOfWeek.FRIDAY);
            }
            if (everySaturday) {
                days.add(DayOfWeek.SATURDAY);
            }
            if (everySunday) {
                days.add(DayOfWeek.SUNDAY);
            }
            return days;
        }
    }
}

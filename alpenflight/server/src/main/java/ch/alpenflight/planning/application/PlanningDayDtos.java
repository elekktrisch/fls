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

/**
 * DTOs for the {@code PlanningDay} REST surface (J-6 T-04). Records (immutable,
 * explicit field set); mass-assignment is structurally impossible because the
 * controller binds to the record, not the
 * {@link ch.alpenflight.planning.domain.PlanningDay} aggregate.
 *
 * <p><strong>The load-bearing shape decision (J-6 carve).</strong> Storage is the
 * generic typed-assignment model, but the wire DTO presents the legacy UI's
 * <em>three fixed person pickers</em> — {@code instructorPersonId} /
 * {@code towingPilotPersonId} / {@code flightOperatorPersonId} (all nullable) —
 * over the generic assignment rows (J-6 oracle, {@code MappingExtensions.cs:3302/
 * 3325/3348}). The service maps each well-known {@link
 * ch.alpenflight.planning.domain.PlanningRole} to its per-club assignment type
 * and upserts/clears the row via {@code PlanningDay.assignRole}.
 *
 * <p>The cross-aggregate FK references ({@code locationId} + the three crew
 * person ids) are the typed-id family ({@link LocationId} / {@link PersonId}) so
 * the wire shape matches the masterdata pickers (which serialize {@code ^loc-…} /
 * {@code ^pn-…}) and mirrors the J-5 reservation DTOs. The day's own {@code id}
 * and {@code operatingClubId} stay plain {@link UUID}.
 *
 * <p>{@code operatingClubId} is intentionally absent from the request records:
 * the day is tenant-stamped from the caller's resolved tenant (A04
 * mass-assignment defense — a caller cannot re-key the tenant via the body).
 */
public final class PlanningDayDtos {

    private PlanningDayDtos() {}

    /**
     * Detail / overview projection. Carries the 3 well-known crew person ids
     * (nullable) resolved from the generic assignment rows, the day's
     * {@code planningDate} / {@code locationId} / free-text {@code info}, the
     * <em>computed</em> {@code numberOfAircraftReservations} (legacy
     * {@code NumberOfAircraftReservations} — count of this day's reservations,
     * never stored), and the {@code canUpdateRecord} / {@code canDeleteRecord}
     * flags driving the UI's edit/delete affordances (ClubAdmin OR record
     * creator; legacy {@code CanUpdate/CanDeleteRecord}).
     */
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

    /**
     * Create payload. {@code planningDate} + {@code locationId} are required; the
     * three crew person ids are all optional (a day may start with no crew).
     * A duplicate {@code (club, date, location)} is rejected 409 by the
     * repository's {@code ux_pln_club_date_loc} dedup.
     */
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

    /**
     * Non-mutating uniqueness pre-check payload (J-6b T-05). The FE
     * inline-validation (T-07) posts the candidate {@code (date, location)} WHILE
     * EDITING to surface the same {@code (club, date, location)} duplicate the save
     * path enforces (the J-6 {@code ux_pln_club_date_loc} 409) before a full save
     * round-trip — NO new rule. The club is the caller's tenant (never on the wire,
     * mirroring the create/update DTOs — A04 mass-assignment defense).
     * {@code excludePlanningDayId} is the day's own id on an <em>edit</em> so it is
     * not flagged against itself (mirrors the bulk skip + the update path); absent
     * on a create check.
     */
    @Schema(description = "Candidate (date, location) to pre-check for a duplicate planning day (non-mutating).")
    public record PlanningDayValidateRequest(
            @NotNull LocalDate planningDate,
            @NotNull LocationId locationId,
            @Nullable @Schema(description = "On an edit, the planning day's own id — excluded from the "
                    + "uniqueness probe so it does not self-conflict. Absent on a create check.")
                    UUID excludePlanningDayId) {}

    /**
     * Field-level validation result (J-6b T-05). Mirrors the reservation
     * {@code ReservationValidationResult} envelope EXACTLY ({@code {valid, field,
     * message}}) so the FE (T-07) reuses the same inline-error mapping onto
     * {@code <af-field-errors>}. {@code field} names the form field the message
     * attaches to — the planning-day duplicate keys on date + location, surfaced on
     * {@code "planningDate"} (the day's primary identity field); {@code message} is
     * the human-readable text. On {@code valid=true} both {@code field} and
     * {@code message} are {@code null}. Returned 200 (a validation OUTCOME, not a
     * request error) — distinct from the save path's 409 problem response.
     */
    @Schema(description = "Field-level validation outcome for an inline pre-check (200; valid flag + offending field).")
    public record PlanningDayValidationResult(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean valid,
            @Nullable String field,
            @Nullable String message) {

        /** A passing result — no offending field, no message. */
        public static PlanningDayValidationResult passed() {
            return new PlanningDayValidationResult(true, null, null);
        }

        /** A failing result attaching {@code message} to {@code field}. */
        public static PlanningDayValidationResult failed(String field, String message) {
            return new PlanningDayValidationResult(false, field, message);
        }
    }

    /**
     * SPA paged envelope for {@code POST .../page/{start}/{size}} — the legacy
     * {@code {Items, PageStart, PageSize, TotalRows}} shape in AlpenFlight house
     * camelCase ({@code items/pageStart/pageSize/totalRows}). {@code totalRows}
     * is the unpaged tenant-scoped count so the SPA renders pagination in one
     * round-trip.
     */
    @Schema(description = "Paged planning-day list envelope (SPA-compat page shape).")
    public record PlanningDayPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PlanningDayDetail> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageSize,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalRows) {}

    /**
     * Legacy {@code PageableSearchFilter}-shaped paged-list request. For J-6 the
     * load-bearing field is the {@code Day.From} date filter (only future days at
     * or after it appear); {@code sorting} is honoured for completeness
     * ({@code planningDate: asc|desc}, default asc — legacy default sort). Both
     * members optional — an empty body returns the future-from-today page sorted
     * {@code planning_date asc}.
     */
    @Schema(description = "Legacy PageableSearchFilter-shaped paged-list request (Day.From filter + sorting).")
    public record PlanningDayPageRequest(
            @Nullable @Schema(description = "Column→direction map; only `planningDate: asc|desc` honoured (default asc).")
                    Map<String, String> sorting,
            @Nullable PlanningDaySearchFilter searchFilter) {}

    @Schema(description = "Basic paged-list filter — the legacy Day.From lower-bound date (J-6 scope).")
    public record PlanningDaySearchFilter(
            @Nullable @Schema(description = "Lower-bound planning date (legacy `Day.From`); default today.")
                    LocalDate from) {}

    /**
     * Bulk weekday-expansion rule (T-05; legacy {@code PlanningDayCreatorRule}).
     * Expands the <em>inclusive</em> {@code [startDate, endDate]} range to one
     * bare planning day (no crew) per matching weekday at {@code locationId},
     * sharing the optional {@code info}. Empty weekday flags → no days created
     * (no error); a day that already exists for the (club, date, location) is
     * skipped idempotently; a range wider than {@code PlanningDay.MAX_RULE_SPAN_DAYS}
     * is rejected 422.
     */
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

        /** The selected weekdays as a {@link DayOfWeek} set (empty if none flagged). */
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

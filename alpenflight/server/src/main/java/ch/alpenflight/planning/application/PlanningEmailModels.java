package ch.alpenflight.planning.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Small, typed view-models the planning-notification Thymeleaf templates bind to
 * (J-6 T-10b). Each maps 1:1 to one template under {@code templates/email/}:
 *
 * <ul>
 *   <li>{@link PlanningDayInfoModel} → {@code planningday-ok.html} /
 *       {@code planningday-cancel.html} (the imminent day+1 club mail; the
 *       ok-vs-cancel choice is the {@code Club} rule, T-10c selects the template).</li>
 *   <li>{@link PlanningDayAssignmentModel} → {@code planningday-assignment-notification.html}
 *       (the week-ahead day+7 reminder to one assigned person).</li>
 * </ul>
 *
 * <p>The job (T-10c) builds these and hands them to
 * {@link ch.alpenflight.platform.mail.TemplatedMailService} as the template model
 * (via {@link #asModel()}). They carry only render data — no behavior, no entities.
 */
public final class PlanningEmailModels {

    private PlanningEmailModels() {}

    /** Model key the templates bind under (e.g. {@code ${planningDay.locationName}}). */
    public static final String PLANNING_DAY_KEY = "planningDay";

    /** Model key the assignment template binds under (e.g. {@code ${assignment.personName}}). */
    public static final String ASSIGNMENT_KEY = "assignment";

    /**
     * The imminent (day+1) planning-day mail model — renders both
     * {@code planningday-ok} and {@code planningday-cancel}. Grounded in the
     * legacy {@code PlanningDayInfoModel} (date, location, the day's crew + the
     * reservation summary the "takes place" mail lists).
     *
     * @param date the planning day's date
     * @param locationName the day's location
     * @param remarks free-text remarks for the day (nullable)
     * @param crew the day's assigned crew, role-label → person-name (may be empty)
     * @param reservations one display line per aircraft reservation (empty for the cancel mail)
     */
    public record PlanningDayInfoModel(
            LocalDate date,
            String locationName,
            @Nullable String remarks,
            List<CrewLine> crew,
            List<String> reservations) {

        public PlanningDayInfoModel {
            crew = List.copyOf(crew);
            reservations = List.copyOf(reservations);
        }

        /** Wraps this model under {@link #PLANNING_DAY_KEY} for the template engine. */
        public Map<String, Object> asModel() {
            return Map.of(PLANNING_DAY_KEY, this);
        }

        /** One crew row: the role label (e.g. "Fluglehrer") and the assigned person's name. */
        public record CrewLine(String role, String personName) {}
    }

    /**
     * The week-ahead (day+7) reminder model for one assigned person — renders
     * {@code planningday-assignment-notification}. Field set mirrors the legacy
     * oracle ({@code PlanningDayEmailBuildService.cs:81-90}).
     *
     * @param date the planning day's date
     * @param locationName the day's location
     * @param remarks free-text remarks for the day (nullable)
     * @param personName the assigned person's name
     * @param assignmentTypeName the assignment-type / role name (e.g. "Fluglehrer")
     * @param appUrl a deep link back into the app
     * @param senderName the sender's display name / address
     */
    public record PlanningDayAssignmentModel(
            LocalDate date,
            String locationName,
            @Nullable String remarks,
            String personName,
            String assignmentTypeName,
            String appUrl,
            String senderName) {

        /** Wraps this model under {@link #ASSIGNMENT_KEY} for the template engine. */
        public Map<String, Object> asModel() {
            return Map.of(ASSIGNMENT_KEY, this);
        }
    }
}

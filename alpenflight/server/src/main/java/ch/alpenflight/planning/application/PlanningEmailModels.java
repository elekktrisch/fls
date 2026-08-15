package ch.alpenflight.planning.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class PlanningEmailModels {

    private PlanningEmailModels() {}

    public static final String PLANNING_DAY_KEY = "planningDay";

    public static final String ASSIGNMENT_KEY = "assignment";

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

        public Map<String, Object> asModel() {
            return Map.of(PLANNING_DAY_KEY, this);
        }

        public record CrewLine(String role, String personName) {}
    }

    public record PlanningDayAssignmentModel(
            LocalDate date,
            String locationName,
            @Nullable String remarks,
            String personName,
            String assignmentTypeName,
            String appUrl,
            String senderName) {

        public Map<String, Object> asModel() {
            return Map.of(ASSIGNMENT_KEY, this);
        }
    }
}

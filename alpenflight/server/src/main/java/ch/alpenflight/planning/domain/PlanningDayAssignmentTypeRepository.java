package ch.alpenflight.planning.domain;

import java.util.List;

public interface PlanningDayAssignmentTypeRepository {

    List<PlanningDayAssignmentType> findActiveTypes();
}

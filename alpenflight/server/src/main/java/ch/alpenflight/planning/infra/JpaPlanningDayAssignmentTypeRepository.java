package ch.alpenflight.planning.infra;

import ch.alpenflight.planning.domain.PlanningDayAssignmentType;
import ch.alpenflight.planning.domain.PlanningDayAssignmentTypeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JpaPlanningDayAssignmentTypeRepository
        extends JpaRepository<PlanningDayAssignmentType, UUID>,
                PlanningDayAssignmentTypeRepository {

    @Override
    @Query("select t from PlanningDayAssignmentType t where t.deletedOn is null "
            + "order by t.assignmentTypeName asc")
    List<PlanningDayAssignmentType> findActiveTypes();
}

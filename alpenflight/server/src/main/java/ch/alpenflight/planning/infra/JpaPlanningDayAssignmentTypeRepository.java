package ch.alpenflight.planning.infra;

import ch.alpenflight.planning.domain.PlanningDayAssignmentType;
import ch.alpenflight.planning.domain.PlanningDayAssignmentTypeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for the tenant-scoped
 * {@link PlanningDayAssignmentType} per-club lookup (J-6).
 *
 * <p>The three well-known role types ({@code segelflugleiter} /
 * {@code schlepppilot} / {@code fluglehrer}) are seeded per club (clean-seed
 * T-06; migration brings a migrated club's own types). The service layer (T-04)
 * resolves a {@code PlanningRole} → type-id from {@link #findActiveTypes} when
 * mapping the 3 form pickers to generic assignment rows.
 *
 * <p>This plain {@code JpaRepository} also gives the type aggregate a
 * discoverable Spring Data binding the way every other {@code @TenantId}
 * aggregate has one: the S-024 leakage sweep ({@code LeakageSweepIT}) requires
 * one per tenant-scoped entity to drive its create-as-A / invisible-to-B /
 * NO_TENANT-sentinel-fails assertions.
 *
 * <p>Hibernate's {@code @TenantId} on
 * {@link PlanningDayAssignmentType#getOperatingClubId()} scopes the inherited
 * finders to the caller's tenant automatically.
 */
public interface JpaPlanningDayAssignmentTypeRepository
        extends JpaRepository<PlanningDayAssignmentType, UUID>,
                PlanningDayAssignmentTypeRepository {

    /** Active (non-deleted) assignment types within the caller's tenant. */
    @Override
    @Query("select t from PlanningDayAssignmentType t where t.deletedOn is null "
            + "order by t.assignmentTypeName asc")
    List<PlanningDayAssignmentType> findActiveTypes();
}

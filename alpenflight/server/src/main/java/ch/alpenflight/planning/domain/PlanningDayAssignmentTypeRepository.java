package ch.alpenflight.planning.domain;

import java.util.List;

/**
 * Domain port for {@link PlanningDayAssignmentType} reads (J-6 T-04).
 * Implemented by {@code ch.alpenflight.planning.infra.JpaPlanningDayAssignmentTypeRepository}
 * (Spring Data). The application layer depends on this port, not the infra
 * implementation (ADR 0023) — mirroring the {@link PlanningDayRepository}
 * split.
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} discriminator on
 * {@code operating_club_id} (ADR 0008): the finder returns only the caller's
 * club's types, so the service's well-known {@link PlanningRole} → type-id
 * resolution is per-club by construction.
 */
public interface PlanningDayAssignmentTypeRepository {

    /** Active (non-deleted) assignment types within the caller's tenant. */
    List<PlanningDayAssignmentType> findActiveTypes();
}

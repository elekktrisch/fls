package ch.alpenflight.joinrequests.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link JoinRequest} persistence (S-178). Implemented by
 * {@code ch.alpenflight.joinrequests.infra.JpaJoinRequestRepository}, which
 * extends both this port and Spring Data's {@code JpaRepository<JoinRequest, UUID>}
 * so the application layer depends on the abstract port (ADR 0023) while Spring
 * Data still generates the runtime bean.
 *
 * <p>Reads come in two tenancy flavours. {@link #findLatestBySub} is the pilot's
 * "my latest request" lookup — the caller has no {@code t_user} / tenant yet, so
 * the service runs it under a lookup-window context (T-05) and the query itself
 * is keyed only on the KC sub. {@link #findPendingForCurrentTenant} is the admin
 * pending-list — Hibernate's {@code @TenantId} discriminator scopes it to the
 * caller's club.
 */
public interface JoinRequestRepository {

    /** Returns the request with the given id, or empty if absent / out-of-tenant. */
    Optional<JoinRequest> findById(UUID id);

    /**
     * The caller's most recent request by {@code created_on}, regardless of
     * status — the pilot's {@code GET /api/v1/me/join-request} read. Keyed only
     * on the KC sub; the caller runs it under a lookup-window context because a
     * not-yet-member has no tenant.
     */
    Optional<JoinRequest> findLatestBySub(UUID keycloakSub);

    /**
     * The caller's tenant's {@link JoinRequestStatus#PENDING} requests, oldest
     * first — the admin pending-list. Tenant-scoped by the {@code @TenantId}
     * discriminator; no explicit {@code club_id} predicate is needed.
     */
    List<JoinRequest> findPendingForCurrentTenant();

    /** Persist (insert or update). Returns the managed entity. */
    JoinRequest save(JoinRequest request);
}

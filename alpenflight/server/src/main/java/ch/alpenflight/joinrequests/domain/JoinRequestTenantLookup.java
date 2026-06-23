package ch.alpenflight.joinrequests.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a join-request's owning {@code club_id} BEFORE a tenant context
 * exists (S-178). A pilot who has not yet joined a club carries no
 * {@code clubId} claim and no {@code t_user} row, so the tenant resolver
 * yields {@code NO_TENANT} and Hibernate's {@code @TenantId} discriminator
 * filters every {@link JoinRequest} read to zero rows. This port is the
 * structurally-pre-tenant escape: it learns the request's tenant by
 * {@code keycloak_sub} (the pilot's me-read) or by request id (the pilot's
 * withdraw), so the service can then load + mutate the aggregate under
 * {@code Tenants.runAs(clubId)} through the ordinary JPA path.
 *
 * <p>Implemented with {@code JdbcTemplate} in {@code joinrequests.infra} —
 * the one native-SQL seam this slice owns, registered in
 * {@code native-sql-register.md}. It returns only the resolution key, never
 * the aggregate; the aggregate load stays JPA + tenant-scoped.
 */
public interface JoinRequestTenantLookup {

    /**
     * The {@code club_id} of the caller's most recent request by
     * {@code created_on}, regardless of status, or empty when the sub has
     * filed none.
     */
    Optional<UUID> findLatestClubIdBySub(UUID keycloakSub);

    /** The owning {@code club_id} of the request with this id, or empty if none. */
    Optional<UUID> findClubIdById(UUID id);
}

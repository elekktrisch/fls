package ch.alpenflight.joinrequests.infra;

import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.joinrequests.domain.JoinRequestRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA implementation of the {@link JoinRequestRepository} port.
 * Extending both this port and {@code JpaRepository<JoinRequest, UUID>} lets
 * Spring Data generate the runtime bean while the application layer depends on
 * the abstract port (ADR 0023). No {@code @Repository} stereotype — Spring
 * Data's repository auto-configuration registers the bean from the interface.
 *
 * <p>{@code findLatestBySub} is not {@code @TenantId}-filtered by an explicit
 * predicate (the discriminator still applies when a tenant is set); its caller
 * (the pilot's me-read) runs it under a lookup-window context, T-05.
 */
public interface JpaJoinRequestRepository
        extends JpaRepository<JoinRequest, UUID>, JoinRequestRepository {

    @Override
    @Query("select r from JoinRequest r where r.keycloakSub = :keycloakSub "
            + "order by r.createdOn desc limit 1")
    Optional<JoinRequest> findLatestBySub(UUID keycloakSub);

    @Override
    @Query("select r from JoinRequest r where r.status = "
            + "ch.alpenflight.joinrequests.domain.JoinRequestStatus.PENDING "
            + "order by r.createdOn asc")
    List<JoinRequest> findPendingForCurrentTenant();

    @Override
    @Query("select r.decidedOn from JoinRequest r where r.keycloakSub = :keycloakSub "
            + "and r.clubId = :clubId and r.status = "
            + "ch.alpenflight.joinrequests.domain.JoinRequestStatus.DENIED "
            + "and r.decidedOn is not null order by r.decidedOn desc limit 1")
    Optional<Instant> findLatestDeniedDecidedOn(UUID keycloakSub, UUID clubId);
}

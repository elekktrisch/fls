package ch.alpenflight.joinrequests.infra;

import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.joinrequests.domain.JoinRequestRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

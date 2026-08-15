package ch.alpenflight.joinrequests.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JoinRequestRepository {

    Optional<JoinRequest> findById(UUID id);

    Optional<JoinRequest> findLatestBySub(UUID keycloakSub);

    List<JoinRequest> findPendingForCurrentTenant();

    Optional<Instant> findLatestDeniedDecidedOn(UUID keycloakSub, UUID clubId);

    JoinRequest save(JoinRequest request);
}

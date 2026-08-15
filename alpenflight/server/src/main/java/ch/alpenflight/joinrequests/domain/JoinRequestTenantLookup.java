package ch.alpenflight.joinrequests.domain;

import java.util.Optional;
import java.util.UUID;

public interface JoinRequestTenantLookup {

    Optional<UUID> findLatestClubIdBySub(UUID keycloakSub);

    Optional<UUID> findClubIdById(UUID id);
}

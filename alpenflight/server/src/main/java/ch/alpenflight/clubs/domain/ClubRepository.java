package ch.alpenflight.clubs.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClubRepository {

    List<Club> findAllActive();

    long countActive();

    List<UUID> activeIds();

    Optional<Club> findActiveById(UUID id);

    boolean existsBySlug(String slug);

    Optional<Club> findActiveBySlug(String slug);

    boolean existsBySlugExcluding(String slug, UUID excludeId);

    boolean existsByJoinCodeIncludingDeleted(String joinCode);

    Optional<UUID> findActiveIdByJoinCode(String joinCode);

    List<UUID> findIdsByDeploymentId(UUID deploymentId);

    Club save(Club club);
}

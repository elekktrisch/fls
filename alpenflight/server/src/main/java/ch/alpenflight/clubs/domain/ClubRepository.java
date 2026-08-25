package ch.alpenflight.clubs.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClubRepository {

    List<Club> findAllActive();

    List<Club> findAllActiveInSameDeploymentAs(UUID readingClubId);

    long countActive();

    long countActiveExcludingDeployment(UUID excludedDeploymentId);

    List<UUID> activeIds();

    List<UUID> activeIdsExcludingDeployment(UUID excludedDeploymentId);

    Optional<Club> findActiveById(UUID id);

    boolean existsBySlug(String slug);

    Optional<Club> findActiveBySlug(String slug);

    boolean existsBySlugExcluding(String slug, UUID excludeId);

    boolean existsByJoinCodeIncludingDeleted(String joinCode);

    Optional<UUID> findActiveIdByJoinCode(String joinCode);

    List<UUID> findIdsByDeploymentId(UUID deploymentId);

    List<UUID> idsOfEveryClubIncludingTheSoftDeleted();

    Club save(Club club);
}

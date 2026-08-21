package ch.alpenflight.clubs.infra;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JpaClubRepository extends JpaRepository<Club, UUID>, ClubRepository {

    @Override
    @Query("select c from Club c where c.deletedOn is null order by c.clubname")
    List<Club> findAllActive();

    @Override
    @Query("select count(c) from Club c where c.deletedOn is null")
    long countActive();

    @Override
    @Query("select c.id from Club c where c.deletedOn is null order by c.id")
    List<UUID> activeIds();

    @Override
    @Query("select c from Club c where c.id = :id and c.deletedOn is null")
    Optional<Club> findActiveById(UUID id);

    @Override
    @Query("select case when count(c) > 0 then true else false end from Club c "
            + "where c.slug = :slug and c.deletedOn is null")
    boolean existsBySlug(String slug);

    @Override
    @Query("select c from Club c where c.slug = :slug and c.deletedOn is null")
    Optional<Club> findActiveBySlug(String slug);

    @Override
    @Query("select case when count(c) > 0 then true else false end from Club c "
            + "where c.slug = :slug and c.id <> :excludeId and c.deletedOn is null")
    boolean existsBySlugExcluding(String slug, UUID excludeId);

    @Override
    @Query("select case when count(c) > 0 then true else false end from Club c "
            + "where c.joinCode = :joinCode")
    boolean existsByJoinCodeIncludingDeleted(String joinCode);

    @Override
    @Query("select c.id from Club c where c.joinCode = :joinCode and c.deletedOn is null")
    Optional<UUID> findActiveIdByJoinCode(String joinCode);

    @Override
    @Query("select c.id from Club c where c.deploymentId = :deploymentId "
            + "and c.deletedOn is null order by c.id")
    List<UUID> findIdsByDeploymentId(UUID deploymentId);

    @Override
    @Query("select c.id from Club c order by c.id")
    List<UUID> idsOfEveryClubIncludingTheSoftDeleted();
}

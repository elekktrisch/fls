package ch.alpenflight.clubs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for {@link Club}. Soft-delete is filtered at the query
 * layer (V2's {@code deleted_on} column) rather than via a Hibernate
 * {@code @SQLRestriction} so the soft-delete contract is locally visible.
 */
@Repository
public interface ClubsRepository extends JpaRepository<Club, UUID> {

    @Query("select c from Club c where c.deletedOn is null order by c.clubname")
    List<Club> findAllActive();

    @Query("select c from Club c where c.id = :id and c.deletedOn is null")
    Optional<Club> findActiveById(UUID id);

    @Query("select case when count(c) > 0 then true else false end from Club c "
            + "where c.slug = :slug and c.deletedOn is null")
    boolean existsBySlug(String slug);

    @Query("select case when count(c) > 0 then true else false end from Club c "
            + "where c.slug = :slug and c.id <> :excludeId and c.deletedOn is null")
    boolean existsBySlugExcluding(String slug, UUID excludeId);
}

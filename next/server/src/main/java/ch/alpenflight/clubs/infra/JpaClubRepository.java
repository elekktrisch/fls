package ch.alpenflight.clubs.infra;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA implementation of the {@link ClubRepository} domain
 * port. Extending both this interface and {@code JpaRepository<Club, UUID>}
 * means Spring Data generates the runtime bean while the application
 * layer still depends on the abstract port (ADR 0023). No
 * {@code @Repository} stereotype — Spring Data's
 * {@code JpaRepositoriesAutoConfiguration} registers the bean from the
 * interface declaration alone.
 *
 * <p>Soft-delete is filtered at the query layer (V2's {@code deleted_on}
 * column) rather than via a Hibernate {@code @SQLRestriction} so the
 * contract stays locally visible.
 */
public interface JpaClubRepository extends JpaRepository<Club, UUID>, ClubRepository {

    @Override
    @Query("select c from Club c where c.deletedOn is null order by c.clubname")
    List<Club> findAllActive();

    @Override
    @Query("select c from Club c where c.id = :id and c.deletedOn is null")
    Optional<Club> findActiveById(UUID id);

    @Override
    @Query("select case when count(c) > 0 then true else false end from Club c "
            + "where c.slug = :slug and c.deletedOn is null")
    boolean existsBySlug(String slug);

    @Override
    @Query("select case when count(c) > 0 then true else false end from Club c "
            + "where c.slug = :slug and c.id <> :excludeId and c.deletedOn is null")
    boolean existsBySlugExcluding(String slug, UUID excludeId);
}

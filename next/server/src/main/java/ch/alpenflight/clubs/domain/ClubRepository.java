package ch.alpenflight.clubs.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link Club} persistence. Implemented by
 * {@code ch.alpenflight.clubs.infra.JpaClubRepository} which extends both
 * this interface and Spring Data's {@code JpaRepository<Club, UUID>} so
 * the application layer depends on the abstract port (ADR 0023) while
 * Spring Data still generates the runtime implementation.
 *
 * <p>Soft-delete (V2's {@code deleted_on} column) is encoded in the JPQL
 * of the JPA extension; this interface speaks the same "active rows
 * only" contract.
 */
public interface ClubRepository {

    /** Returns active (non-soft-deleted) clubs, ordered by name. */
    List<Club> findAllActive();

    /** Returns the active club with the given id, or empty if absent / soft-deleted. */
    Optional<Club> findActiveById(UUID id);

    /** True iff an active club exists with the given slug. */
    boolean existsBySlug(String slug);

    /** True iff an active club other than {@code excludeId} exists with the given slug. */
    boolean existsBySlugExcluding(String slug, UUID excludeId);

    /** Persist (insert or update). Returns the managed entity. */
    Club save(Club club);
}

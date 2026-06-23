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

    /** Count of active (non-soft-deleted) clubs — sysadmin dashboard total (J-3 T-10). */
    long countActive();

    /** Ids of every active (non-soft-deleted) club — drives the sysadmin cross-tenant flight tally (J-3 T-10). */
    List<UUID> activeIds();

    /** Returns the active club with the given id, or empty if absent / soft-deleted. */
    Optional<Club> findActiveById(UUID id);

    /** True iff an active club exists with the given slug. */
    boolean existsBySlug(String slug);

    /** True iff an active club other than {@code excludeId} exists with the given slug. */
    boolean existsBySlugExcluding(String slug, UUID excludeId);

    /**
     * True iff any club — active or soft-deleted — already carries this join
     * code. The {@code ux_club_join_code} index is global and unfiltered, so
     * the rotation collision-check must span every row, not just active ones.
     */
    boolean existsByJoinCode(String joinCode);

    /**
     * Returns the ids of every Club under {@code deploymentId}, ordered by
     * id (deterministic for partial-failure resumption per the Performance
     * plan). Drives {@code DeploymentContext.forEachClub}; the per-Club
     * row load runs under that Club's tenant scope.
     *
     * <p>Projection-only — never eager-loads the Club aggregate. Tenant
     * scoping happens later in the iteration, after the caller switches
     * to each Club's own context.
     */
    List<UUID> findIdsByDeploymentId(UUID deploymentId);

    /** Persist (insert or update). Returns the managed entity. */
    Club save(Club club);
}

package ch.alpenflight.users.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for {@link User} persistence. Implemented by
 * {@code ch.alpenflight.users.infra.JpaUserRepository}.
 *
 * <p>User is cross-tenant (no Hibernate {@code @TenantId}); tenant scoping
 * for the CLUB_ADMINISTRATOR surface is enforced explicitly via the
 * {@code club_id} predicate on every list / count query. The {@code findActiveById}
 * load is intentionally cross-tenant so the service layer can apply the
 * 404-not-403 IDOR contract uniformly.
 */
public interface UserRepository {

    /**
     * Compact projection for the {@code GET /api/v1/users} list. Carries
     * {@code keycloakSub} so the service layer can index live KC fields
     * (enabled / requiredActions) per row without re-loading the User
     * entity.
     */
    record ListRow(UUID id,
                   UUID clubId,
                   String username,
                   String friendlyName,
                   String notificationEmail,
                   @Nullable UUID personId,
                   @Nullable String phoneNumber,
                   UUID languageId,
                   @Nullable UUID keycloakSub) {}

    List<ListRow> findActiveInClub(UUID clubId);

    Optional<User> findActiveById(UUID id);

    Optional<User> findActiveByKeycloakSub(UUID keycloakSub);

    /**
     * Lookup by {@code keycloak_sub} including soft-deleted rows. The JIT
     * filter calls this to fire the soft-delete gate on the residual-JWT
     * window. The {@code invite} flow calls it to detect a tombstone that
     * needs its {@code keycloak_sub} cleared before a new row can re-use
     * the identity.
     */
    Optional<User> findAnyByKeycloakSub(UUID keycloakSub);

    Optional<User> findActiveByUsernameLower(String username);

    /**
     * Count of active users in a club. The service layer uses it together
     * with a JWT-roles read to decide whether removing the last
     * CLUB_ADMINISTRATOR would orphan the club.
     */
    long countActiveInClub(UUID clubId);

    /**
     * Count of active users across ALL clubs (no tenant predicate). Feeds the
     * sysadmin dashboard's {@code totalUsers} tile (J-3 T-10). User has no
     * {@code @TenantId}, so this is a plain unscoped count — the opposite of
     * the per-club {@link #countActiveInClub(UUID)}.
     */
    long countAllActive();

    /**
     * True iff a {@code t_language} row with this id exists. Lets the
     * application reject an unknown {@code languageId} with a clean 400 rather
     * than letting the {@code fk_user_language_id} FK fail at flush (which
     * would surface as a 500). The language table is a small static reference
     * set (V2 seed).
     */
    boolean languageExists(UUID languageId);

    User save(User user);

    void flush();
}

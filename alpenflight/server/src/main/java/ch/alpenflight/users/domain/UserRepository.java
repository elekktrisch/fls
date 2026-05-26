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

    /** Compact projection for the {@code GET /api/v1/users} list. */
    record ListRow(UUID id,
                   UUID clubId,
                   String username,
                   String friendlyName,
                   String notificationEmail,
                   @Nullable UUID personId,
                   @Nullable String phoneNumber,
                   UUID languageId) {}

    List<ListRow> findActiveInClub(UUID clubId);

    Optional<User> findActiveById(UUID id);

    Optional<User> findActiveByKeycloakSub(UUID keycloakSub);

    Optional<User> findActiveByUsernameLower(String username);

    /**
     * Count of active users in a club. The service layer uses it together
     * with a JWT-roles read to decide whether removing the last
     * CLUB_ADMINISTRATOR would orphan the club.
     */
    long countActiveInClub(UUID clubId);

    User save(User user);

    void flush();
}

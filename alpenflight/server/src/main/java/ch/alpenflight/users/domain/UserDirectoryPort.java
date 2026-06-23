package ch.alpenflight.users.domain;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for the external user-directory service (Keycloak, in this
 * codebase). The {@code users.application} layer talks to this interface;
 * the {@code users.infra.keycloak} adapter implements it. Per ADR 0023,
 * application code must not import the adapter directly.
 *
 * <p>Method names mirror the KC admin REST verbs because the realm is the
 * source of truth, but the parameter / return types are domain-shaped
 * records (no Jackson annotations, no transport types).
 */
public interface UserDirectoryPort {

    /**
     * Create a directory entry. Returns the server-assigned {@code sub}
     * (UUID) on success. Throws {@link UserDirectoryException} on any
     * upstream failure — including duplicate username (HTTP 409).
     */
    UUID createUser(UserDirectorySpec spec);

    /**
     * Delete a directory entry. Used only for compensating actions after a
     * local-row insert failure — the operator-visible delete path uses
     * {@link #setEnabled} instead so the upstream event log stays intact.
     */
    void deleteUser(UUID sub);

    /** Flip the directory entry's {@code enabled} flag (true = active). */
    void setEnabled(UUID sub, boolean enabled);

    /**
     * List users in a club. The implementation MUST scope by
     * {@code clubId} (the realm is shared across all tenants); a forgotten
     * scope is the leak the security plan calls out.
     *
     * @param clubId   tenant scope; must not be {@code null}
     * @param max      directory-side page cap
     */
    List<UserDirectoryRow> findUsersInClub(UUID clubId, int max);

    /**
     * Write the {@code clubId} user-attribute on an existing directory entry,
     * leaving the user's other attributes (e.g. {@code locale}) intact. The
     * club-join approval (S-178) calls this so the now-member's next-issued JWT
     * carries the {@code clubId} claim the tenant resolver + JIT materializer
     * read. Idempotent: writing the same value is a no-op, so a re-approve or a
     * post-failure retry is safe.
     */
    void writeClubIdAttribute(UUID sub, UUID clubId);

    /** Read the realm role-mappings for one user. */
    List<RealmRoleRef> getRealmRoleMappings(UUID sub);

    /**
     * Read all users carrying a given realm role. Used by the last-admin
     * orphan guard to avoid an N+1 fan-out on soft-delete.
     */
    List<UserDirectoryRow> findUsersByRoleName(String roleName);

    /** Resolve realm-role names to typed {@link RealmRoleRef}s (carries the id field the directory's grant API needs). */
    List<RealmRoleRef> findRealmRolesByName(Set<String> names);

    void grantRealmRoles(UUID sub, List<RealmRoleRef> roles);

    void revokeRealmRoles(UUID sub, List<RealmRoleRef> roles);

    /**
     * Fire the directory's required-action email (e.g. UPDATE_PASSWORD) for
     * a user. Best-effort: the caller must surface failures but not roll
     * the business transaction back on them.
     */
    void sendExecuteActions(UUID sub, List<String> actions, Duration lifespan);

    /** Payload for {@link #createUser}. */
    record UserDirectorySpec(
            String username,
            String email,
            String firstName,
            String lastName,
            UUID clubId,
            @Nullable String locale,
            List<String> requiredActions,
            boolean enabled) {}

    /** Domain projection of a directory user row. */
    record UserDirectoryRow(
            UUID id,
            @Nullable String username,
            @Nullable String email,
            @Nullable Boolean enabled,
            @Nullable List<String> requiredActions,
            @Nullable Long createdTimestamp) {}

    /** Realm-role reference shape the directory's grant API expects. */
    record RealmRoleRef(
            @Nullable String id,
            String name,
            @Nullable String description) {}
}

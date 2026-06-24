package ch.alpenflight.users.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
     * Look up a directory user by exact email — the admin-invite robustness
     * pre-check (S-181). Returns the user's {@code sub} plus the two attributes
     * the invite branch decides on: {@code clubId} (present ⇒ already attached
     * to a club) and {@code locale} (drives the welcome-attached email). Empty
     * when no directory user carries the email, so the invite falls through to
     * its create path. The realm is shared across tenants, so the lookup is
     * deliberately realm-wide (an email is globally unique in the realm).
     */
    Optional<DirectoryUser> findUserByEmail(String email);

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

    /**
     * Remove the {@code clubId} user-attribute, leaving the user's other
     * attributes intact. The compensation for a half-failed club-join approve
     * (S-178): a {@code clubId} attribute that outlives a rolled-back approve
     * would project into the pilot's next JWT and grant tenant access with no
     * corroborating {@code t_user}, so a failure after {@link #writeClubIdAttribute}
     * must clear it. Idempotent: clearing an absent attribute is a no-op.
     */
    void clearClubIdAttribute(UUID sub);

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

    /**
     * Email-lookup projection for the invite robustness pre-check (S-181). The
     * {@code clubId} attribute is {@code null} for an unattached directory user
     * (signed up but not yet a club member) and present once a club admin /
     * approve has bound them — so {@code clubId != null} is the one-sub-one-club
     * gate. {@code locale} is the BCP-47 string set at signup; null when the IdP
     * carried none.
     */
    record DirectoryUser(UUID sub, @Nullable UUID clubId, @Nullable String locale) {}

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

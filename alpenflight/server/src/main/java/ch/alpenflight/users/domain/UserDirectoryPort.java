package ch.alpenflight.users.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface UserDirectoryPort {

    UUID createUser(UserDirectorySpec spec);

    void deleteUser(UUID sub);

    void setEnabled(UUID sub, boolean enabled);

    Optional<DirectoryUser> findUserByEmailRealmWide(String lowercasedEmail);

    List<UserDirectoryRow> findUsersInClub(UUID clubId, int max);

    void writeClubIdAttribute(UUID sub, UUID clubId);

    void clearClubIdAttribute(UUID sub);

    List<RealmRoleRef> getRealmRoleMappings(UUID sub);

    List<UserDirectoryRow> findUsersByRoleName(String roleName);

    List<RealmRoleRef> findRealmRolesByName(Set<String> names);

    void grantRealmRoles(UUID sub, List<RealmRoleRef> roles);

    void revokeRealmRoles(UUID sub, List<RealmRoleRef> roles);

    void sendExecuteActions(UUID sub, List<String> actions, Duration lifespan);

    record UserDirectorySpec(
            String username,
            String email,
            String firstName,
            String lastName,
            UUID clubId,
            @Nullable String locale,
            List<String> requiredActions,
            boolean enabled) {}

    record DirectoryUser(UUID sub, @Nullable UUID clubId, @Nullable String locale) {

        public static final UUID CORRUPTED_CLUB_ID = new UUID(0L, 0L);
    }

    record UserDirectoryRow(
            UUID id,
            @Nullable String username,
            @Nullable String email,
            @Nullable Boolean enabled,
            @Nullable List<String> requiredActions,
            @Nullable Long createdTimestamp) {}

    record RealmRoleRef(
            @Nullable String id,
            String name,
            @Nullable String description) {}
}

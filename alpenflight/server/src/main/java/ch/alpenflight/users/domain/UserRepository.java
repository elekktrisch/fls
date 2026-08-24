package ch.alpenflight.users.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface UserRepository {

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

    Optional<User> findAnyByKeycloakSub(UUID keycloakSub);

    Optional<User> findActiveByUsernameLower(String username);

    long countActiveInClub(UUID clubId);

    long countAllActive();

    long countAllActiveExcludingDeployment(UUID excludedDeploymentId);

    boolean languageExists(UUID languageId);

    User save(User user);

    void flush();
}

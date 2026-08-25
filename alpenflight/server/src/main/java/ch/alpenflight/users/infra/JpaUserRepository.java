package ch.alpenflight.users.infra;

import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaUserRepository extends JpaRepository<User, UUID>, UserRepository {

    @Override
    @Query("select new ch.alpenflight.users.domain.UserRepository$ListRow("
            + "u.id, u.clubId, u.username, u.friendlyName, u.notificationEmail, "
            + "u.personId, u.phoneNumber, u.languageId, u.keycloakSub) "
            + "from User u "
            + "where u.clubId = :clubId and u.deletedOn is null "
            + "order by lower(u.friendlyName) asc, lower(u.username) asc")
    List<ListRow> findActiveInClub(@Param("clubId") UUID clubId);

    @Override
    @Query("select u from User u where u.id = :id and u.deletedOn is null")
    Optional<User> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select u from User u where u.keycloakSub = :sub and u.deletedOn is null")
    Optional<User> findActiveByKeycloakSub(@Param("sub") UUID keycloakSub);

    @Override
    @Query("select u from User u where u.keycloakSub = :sub")
    Optional<User> findAnyByKeycloakSub(@Param("sub") UUID keycloakSub);

    @Override
    @Query("select u from User u where lower(u.username) = lower(:username) and u.deletedOn is null")
    Optional<User> findActiveByUsernameLower(@Param("username") String username);

    @Override
    @Query("select count(u) from User u where u.clubId = :clubId and u.deletedOn is null")
    long countActiveInClub(@Param("clubId") UUID clubId);

    @Override
    @Query("select count(u) from User u where u.deletedOn is null")
    long countAllActive();

    @Override
    @Query("select count(u) from User u "
            + "join ch.alpenflight.clubs.domain.Club c on c.id = u.clubId "
            + "where u.deletedOn is null and c.deploymentId <> :excludedDeploymentId")
    long countAllActiveExcludingDeployment(
            @Param("excludedDeploymentId") UUID excludedDeploymentId);

    @Override
    @Query("select case when count(l) > 0 then true else false end "
            + "from Language l where l.id = :languageId")
    boolean languageExists(@Param("languageId") UUID languageId);
}

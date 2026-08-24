package ch.alpenflight.persons.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface PersonRepository {

    record ListRow(UUID id,
                   String firstname,
                   String lastname,
                   @Nullable String emailPrivate,
                   @Nullable String mobilePhone,
                   @Nullable String city,
                   @Nullable String zip,
                   @Nullable String memberNumber,
                   @Nullable UUID memberStateId,
                   @Nullable String memberStateName,
                   boolean active,
                   boolean motorPilot,
                   boolean towPilot,
                   boolean gliderInstructor,
                   boolean gliderPilot,
                   boolean gliderTrainee,
                   boolean winchOperator,
                   boolean motorInstructor) {}

    List<ListRow> findActiveListRowsInCurrentTenant();

    Optional<Person> findActiveById(UUID id);

    boolean hasActiveMembershipInCurrentTenant(UUID personId);

    boolean hasActiveMembershipInOtherTenant(UUID personId, UUID currentTenantId);

    long countActiveMembershipsAcrossTenants(UUID personId);

    List<Person> findActiveByEmail(String lowerCasedEmail);

    List<Person> findActiveByEmailInSameDeploymentAs(String lowerCasedEmail, UUID readingClubId);

    List<Person> findActiveByIdentityTriple(String firstname, String lastname, LocalDate birthday);

    List<Person> findActiveByIdentityTripleInSameDeploymentAs(
            String firstname, String lastname, LocalDate birthday, UUID readingClubId);

    Optional<Person> findActiveByIdInSameDeploymentAs(UUID id, UUID readingClubId);

    List<Person> findWithLicenceExpiringOnOrBefore(LocalDate cutoff);

    Person save(Person person);

    void flush();
}

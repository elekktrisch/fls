package ch.alpenflight.persons.infra;

import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPersonRepository extends JpaRepository<Person, UUID>, PersonRepository {

    String JOINED_A_CLUB_OF_THE_READING_CLUBS_DEPLOYMENT =
            "exists ("
                    + "  select 1 from PersonClubMembershipOutsideTheTenantFilter reaching "
                    + "  join ch.alpenflight.clubs.domain.Club joined "
                    + "    on joined.id = reaching.clubId "
                    + "  join ch.alpenflight.clubs.domain.Club reading "
                    + "    on reading.id = :readingClubId "
                    + "    and reading.deploymentId = joined.deploymentId "
                    + "  where reaching.personId = p.id) ";

    String JOINED_NO_CLUB_AND_THE_READING_CLUB_IS_OUTSIDE_EVERY_SANDBOX =
            "(not exists ("
                    + "  select 1 from PersonClubMembershipOutsideTheTenantFilter unjoined "
                    + "  where unjoined.personId = p.id) "
                    + "and exists ("
                    + "  select 1 from ch.alpenflight.clubs.domain.Club readingOutsideSandbox "
                    + "  join ch.alpenflight.deployments.domain.Deployment hostingDeployment "
                    + "    on hostingDeployment.id = readingOutsideSandbox.deploymentId "
                    + "  where readingOutsideSandbox.id = :readingClubId "
                    + "    and hostingDeployment.lifecycleState <> "
                    + "        ch.alpenflight.deployments.domain.LifecycleState.SANDBOX)) ";

    String REACHED_BY_IDENTITY_SEARCH_ONLY_THROUGH_A_CLUB_MEMBERSHIP =
            "and (" + JOINED_A_CLUB_OF_THE_READING_CLUBS_DEPLOYMENT + ") ";

    String REACHED_BY_ID_ALSO_WITHOUT_A_CLUB_MEMBERSHIP =
            "and (" + JOINED_A_CLUB_OF_THE_READING_CLUBS_DEPLOYMENT
                    + "or " + JOINED_NO_CLUB_AND_THE_READING_CLUB_IS_OUTSIDE_EVERY_SANDBOX + ") ";

    @Override
    @Query("select new ch.alpenflight.persons.domain.PersonRepository$ListRow("
            + "p.id, p.firstname, p.lastname, p.emailPrivate, p.mobilePhone, p.city, p.zip, "
            + "pc.memberNumber, pc.memberStateId, ms.name, pc.active, "
            + "pc.motorPilot, pc.towPilot, pc.gliderInstructor, pc.gliderPilot, "
            + "pc.gliderTrainee, pc.winchOperator, pc.motorInstructor) "
            + "from PersonClub pc "
            + "join pc.person p "
            + "left join ch.alpenflight.clubs.domain.MemberState ms on ms.id = pc.memberStateId "
            + "where p.deletedOn is null and pc.deletedOn is null "
            + "order by p.lastname asc, p.firstname asc")
    List<ListRow> findActiveListRowsInCurrentTenant();

    @Override
    @Query("select p from Person p where p.id = :id and p.deletedOn is null")
    Optional<Person> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select case when count(pc) > 0 then true else false end "
            + "from PersonClub pc where pc.person.id = :personId and pc.deletedOn is null")
    boolean hasActiveMembershipInCurrentTenant(@Param("personId") UUID personId);

    @Override
    @Query(value = "SELECT EXISTS ("
            + "  SELECT 1 FROM t_person_club "
            + "  WHERE person_id = :personId "
            + "    AND deleted_on IS NULL "
            + "    AND club_id <> :currentTenantId"
            + ")", nativeQuery = true)
    boolean hasActiveMembershipInOtherTenant(@Param("personId") UUID personId,
                                             @Param("currentTenantId") UUID currentTenantId);

    @Override
    @Query(value = "SELECT COUNT(*) FROM t_person_club "
            + "WHERE person_id = :personId AND deleted_on IS NULL", nativeQuery = true)
    long countActiveMembershipsAcrossTenants(@Param("personId") UUID personId);

    @Override
    @Query("select p from Person p where p.deletedOn is null and ("
            + "lower(p.emailPrivate) = :email or lower(p.emailBusiness) = :email)")
    List<Person> findActiveByEmail(@Param("email") String lowerCasedEmail);

    @Override
    @Query("select p from Person p where p.deletedOn is null and ("
            + "lower(p.emailPrivate) = :email or lower(p.emailBusiness) = :email) "
            + REACHED_BY_IDENTITY_SEARCH_ONLY_THROUGH_A_CLUB_MEMBERSHIP)
    List<Person> findActiveByEmailInSameDeploymentAs(@Param("email") String lowerCasedEmail,
                                                     @Param("readingClubId") UUID readingClubId);

    @Override
    @Query("select p from Person p where p.deletedOn is null "
            + "and lower(p.firstname) = lower(:firstname) "
            + "and lower(p.lastname) = lower(:lastname) "
            + "and p.birthday = :birthday "
            + REACHED_BY_IDENTITY_SEARCH_ONLY_THROUGH_A_CLUB_MEMBERSHIP)
    List<Person> findActiveByIdentityTripleInSameDeploymentAs(
            @Param("firstname") String firstname,
            @Param("lastname") String lastname,
            @Param("birthday") LocalDate birthday,
            @Param("readingClubId") UUID readingClubId);

    @Override
    @Query("select p from Person p where p.id = :id and p.deletedOn is null "
            + REACHED_BY_ID_ALSO_WITHOUT_A_CLUB_MEMBERSHIP)
    Optional<Person> findActiveByIdInSameDeploymentAs(@Param("id") UUID id,
                                                      @Param("readingClubId") UUID readingClubId);

    String HOLDS_NO_MEMBERSHIP_IN_A_CLUB_OF_A_SANDBOX_DEPLOYMENT =
            "not exists ("
                    + "  select 1 from PersonClubMembershipOutsideTheTenantFilter seatMembership "
                    + "  join ch.alpenflight.clubs.domain.Club seatClub "
                    + "    on seatClub.id = seatMembership.clubId "
                    + "  join ch.alpenflight.deployments.domain.Deployment hostingDeployment "
                    + "    on hostingDeployment.id = seatClub.deploymentId "
                    + "  where seatMembership.personId = p.id "
                    + "    and hostingDeployment.lifecycleState = "
                    + "        ch.alpenflight.deployments.domain.LifecycleState.SANDBOX) ";

    @Override
    @Query("select p from Person p where p.deletedOn is null and ("
            + "p.medicalLaplExpireDate <= :cutoff "
            + "or p.medicalClass1ExpireDate <= :cutoff "
            + "or p.medicalClass2ExpireDate <= :cutoff "
            + "or p.gliderInstructorLicenceExpireDate <= :cutoff "
            + "or p.motorInstructorLicenceExpireDate <= :cutoff "
            + "or p.partMLicenceExpireDate <= :cutoff) "
            + "and " + HOLDS_NO_MEMBERSHIP_IN_A_CLUB_OF_A_SANDBOX_DEPLOYMENT)
    List<Person> findWithLicenceExpiringOnOrBeforeOutsideEverySandboxDeployment(
            @Param("cutoff") LocalDate cutoff);

    @Override
    @Query("select p from Person p where p.deletedOn is null "
            + "and lower(p.firstname) = lower(:firstname) "
            + "and lower(p.lastname) = lower(:lastname) "
            + "and p.birthday = :birthday")
    List<Person> findActiveByIdentityTriple(@Param("firstname") String firstname,
                                            @Param("lastname") String lastname,
                                            @Param("birthday") LocalDate birthday);
}

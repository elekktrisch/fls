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
            + "p.medicalLaplExpireDate <= :cutoff "
            + "or p.medicalClass1ExpireDate <= :cutoff "
            + "or p.medicalClass2ExpireDate <= :cutoff "
            + "or p.gliderInstructorLicenceExpireDate <= :cutoff "
            + "or p.motorInstructorLicenceExpireDate <= :cutoff "
            + "or p.partMLicenceExpireDate <= :cutoff)")
    List<Person> findWithLicenceExpiringOnOrBefore(@Param("cutoff") LocalDate cutoff);

    @Override
    @Query("select p from Person p where p.deletedOn is null "
            + "and lower(p.firstname) = lower(:firstname) "
            + "and lower(p.lastname) = lower(:lastname) "
            + "and p.birthday = :birthday")
    List<Person> findActiveByIdentityTriple(@Param("firstname") String firstname,
                                            @Param("lastname") String lastname,
                                            @Param("birthday") LocalDate birthday);
}

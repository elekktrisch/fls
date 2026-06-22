package ch.alpenflight.accounting.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence proof of the indirect-tenancy current-balance read: a
 * {@link PersonFlightTimeCredit} carries no {@code @TenantId}, yet the repo
 * scopes it to the caller's tenant via the owning Person's {@code PersonClub}
 * ({@code DeliveryService.cs:142-146} parity) — exactly the cross-tenant Person
 * pattern. Seeding is ADR-0027-clean: clubs via {@link TwoClubFixture}, members
 * via {@link Person#register} + {@code joinClub}, credits via
 * {@link PersonFlightTimeCredit#grant} + {@link PersonFlightTimeCreditRepository#save}.
 */
class PersonFlightTimeCreditRepositoryIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_PFTC_";
    private static final String KEY_PREFIX = "IT_PC_";

    @Autowired private PersonFlightTimeCreditRepository credits;
    @Autowired private PersonRepository persons;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        jdbc.update("DELETE FROM t_person_flight_time_credit_transaction "
                + "WHERE credit_id IN (SELECT c.id FROM t_person_flight_time_credit c "
                + "JOIN t_person p ON p.id = c.person_id WHERE p.lastname = ?)", NAME_PREFIX);
        jdbc.update("DELETE FROM t_person_flight_time_credit "
                + "WHERE person_id IN (SELECT id FROM t_person WHERE lastname = ?)", NAME_PREFIX);
    }

    @Test
    void currentBalanceRead_returnsTheIsCurrentTransactionBalance_forCallerTenantMember() {
        UUID memberA = seedMember(clubA, "alice");
        UUID creditId = seedCredit(memberA, false, 3_600L);

        TenantTestContext.runAs(clubA, () -> {
            var found = credits.findActiveForPersonInCurrentTenant(memberA);
            assertThat(found)
                    .as("club A sees its own member's credit")
                    .extracting(PersonFlightTimeCredit::getId)
                    .containsExactly(creditId);
            assertThat(found.getFirst().currentBalanceInSeconds())
                    .as("balance = the IsCurrent transaction's CurrentFlightTimeBalanceInSeconds")
                    .isEqualTo(3_600L);
            return null;
        });
    }

    @Test
    void currentBalanceRead_isTenantScoped_anotherTenantsMemberCreditIsInvisible() {
        UUID memberB = seedMember(clubB, "bob");
        seedCredit(memberB, false, 1_800L);

        TenantTestContext.runAs(clubA, () ->
                assertThat(credits.findActiveForPersonInCurrentTenant(memberB))
                        .as("club A cannot load club B's member's credit (indirect tenancy)")
                        .isEmpty());
        TenantTestContext.runAs(clubB, () ->
                assertThat(credits.findActiveForPersonInCurrentTenant(memberB))
                        .as("club B loads its own member's credit")
                        .hasSize(1));
    }

    @Test
    void unlimitedCredit_currentBalanceIsNull() {
        UUID memberA = seedMember(clubA, "carol");
        seedCredit(memberA, true, null);

        TenantTestContext.runAs(clubA, () -> {
            var found = credits.findActiveForPersonInCurrentTenant(memberA);
            assertThat(found).hasSize(1);
            assertThat(found.getFirst().hasCurrentBalance())
                    .as("a current transaction exists")
                    .isTrue();
            assertThat(found.getFirst().currentBalanceInSeconds())
                    .as("NoFlightTimeLimit ⇒ unlimited ⇒ null balance")
                    .isNull();
            return null;
        });
    }

    private UUID seedMember(UUID clubId, String firstname) {
        Person person = Person.register(firstname, NAME_PREFIX, null);
        return TenantTestContext.runAs(clubId, () -> {
            person.joinClub(clubId, null, null,
                    PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
            return persons.save(person).getId().value();
        });
    }

    private UUID seedCredit(UUID personId, boolean unlimited, Long balanceInSeconds) {
        Person person = persons.findActiveById(personId).orElseThrow();
        PersonFlightTimeCredit credit = PersonFlightTimeCredit.grant(
                person,
                Instant.now().plus(365, ChronoUnit.DAYS),
                false, "HBABC", 25,
                unlimited, balanceInSeconds);
        UUID id = credits.save(credit).getId();
        assertThat(id).isNotNull();
        return id;
    }
}

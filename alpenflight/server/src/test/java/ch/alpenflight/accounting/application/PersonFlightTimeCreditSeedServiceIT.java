package ch.alpenflight.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.accounting.application.PersonFlightTimeCreditSeedService.CreditView;
import ch.alpenflight.accounting.application.PersonFlightTimeCreditSeedService.GrantCommand;
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
 * The seed service's read must reconstruct the current balance OUTSIDE any
 * caller-held transaction: the e2e re-GETs a granted credit on a fresh request,
 * so {@code find} owns its own read transaction. {@link CreditView#of} touches
 * the lazy {@code transactions} collection — without a session that throws
 * {@code LazyInitializationException} (a 500 to the caller), so {@code find}
 * must keep a session open for the balance projection.
 */
class PersonFlightTimeCreditSeedServiceIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_PFTCS_";
    private static final String KEY_PREFIX = "IT_PCS_";

    @Autowired private PersonFlightTimeCreditSeedService service;
    @Autowired private PersonRepository persons;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private UUID clubA;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        jdbc.update("DELETE FROM t_person_flight_time_credit_transaction "
                + "WHERE credit_id IN (SELECT c.id FROM t_person_flight_time_credit c "
                + "JOIN t_person p ON p.id = c.person_id WHERE p.lastname = ?)", NAME_PREFIX);
        jdbc.update("DELETE FROM t_person_flight_time_credit "
                + "WHERE person_id IN (SELECT id FROM t_person WHERE lastname = ?)", NAME_PREFIX);
    }

    @Test
    void find_returnsTheCurrentBalance_outsideACallerTransaction() {
        UUID memberA = seedMember(clubA, "dave");
        UUID creditId = TenantTestContext.runAs(clubA,
                () -> service.grant(grant(memberA, false, 1_800L)).id());

        CreditView found = TenantTestContext.runAs(clubA,
                () -> service.find(creditId).orElseThrow());

        assertThat(found.currentFlightTimeBalanceInSeconds())
                .as("the re-read carries the IsCurrent transaction balance, no LazyInitializationException")
                .isEqualTo(1_800L);
        assertThat(found.discountInPercent()).isEqualTo(25);
    }

    @Test
    void find_returnsNullBalance_forAnUnlimitedCredit() {
        UUID memberA = seedMember(clubA, "erin");
        UUID creditId = TenantTestContext.runAs(clubA,
                () -> service.grant(grant(memberA, true, null)).id());

        CreditView found = TenantTestContext.runAs(clubA,
                () -> service.find(creditId).orElseThrow());

        assertThat(found.noFlightTimeLimit()).isTrue();
        assertThat(found.currentFlightTimeBalanceInSeconds())
                .as("NoFlightTimeLimit ⇒ unlimited ⇒ null balance")
                .isNull();
    }

    private GrantCommand grant(UUID personId, boolean unlimited, Long balanceInSeconds) {
        return new GrantCommand(personId, unlimited, false, "HBABC", 25,
                Instant.now().plus(365, ChronoUnit.DAYS), balanceInSeconds);
    }

    private UUID seedMember(UUID clubId, String firstname) {
        Person person = Person.register(firstname, NAME_PREFIX, null);
        return TenantTestContext.runAs(clubId, () -> {
            person.joinClub(clubId, null, null,
                    PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
            return persons.save(person).getId().value();
        });
    }
}

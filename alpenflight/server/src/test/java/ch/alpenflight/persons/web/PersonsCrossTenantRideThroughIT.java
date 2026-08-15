package ch.alpenflight.persons.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonsCrossTenantRideThroughIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_PRT_";
    private static final String KEY_PREFIX = "IT_PR_";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PersonRepository persons;
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
        TenantTestContext.clear();
    }

    @Test
    void findById_returns_person_regardless_of_caller_tenant() {
        UUID personId = UUID.fromString("019e30c3-2c00-7001-8000-00000000cccd");
        UUID pcId = UUID.fromString("019e30c3-2c00-7001-8000-00000000ccce");
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "MultiClub", "Pilot");
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                pcId.toString(), personId.toString(), clubB.toString());

        TenantTestContext.runAs(clubA, () -> {
            Optional<Person> found = persons.findActiveById(personId);
            assertThat(found)
                    .as("Person PK load is cross-tenant by design (sacred cow); "
                            + "no @TenantId / @Filter / @Where may scope it away")
                    .isPresent();
            assertThat(found.get().getFirstname()).isEqualTo("MultiClub");
        });

        TenantTestContext.runAs(clubB, () ->
                assertThat(persons.findActiveById(personId)).isPresent());

        Optional<Person> unscoped = persons.findActiveById(personId);
        assertThat(unscoped)
                .as("Outside any tenant context, Person PK load still resolves — "
                        + "Person is the documented cross-tenant exception per S-011")
                .isPresent();
    }

    @Test
    void findActiveListRowsInCurrentTenant_returns_empty_for_tenant_without_memberships() {
        UUID personId = UUID.fromString("019e30c3-2c00-7001-8000-00000000cce0");
        UUID pcId = UUID.fromString("019e30c3-2c00-7001-8000-00000000cce1");
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "BInB", "Only");
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                pcId.toString(), personId.toString(), clubB.toString());

        TenantTestContext.runAs(clubA, () ->
                assertThat(persons.findActiveListRowsInCurrentTenant())
                        .as("Per-tenant list JOINs through PersonClub; the @TenantId on the "
                                + "junction scopes the result away from foreign-tenant Persons")
                        .extracting(PersonRepository.ListRow::id)
                        .doesNotContain(personId));
    }
}

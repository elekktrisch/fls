package ch.alpenflight.multitenancy.leakage;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonClubTenantFilterAppliesToJoinsIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_PCJ_";
    private static final String KEY_PREFIX = "IT_PJ";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;
    @Autowired private EntityManager entityManager;

    private UUID clubA;
    private UUID clubB;
    private UUID personInBothClubs;

    @BeforeEach
    void seedOnePersonInTwoClubs() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        personInBothClubs = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personInBothClubs.toString(), "TwoClub", NAME_PREFIX);
        insertMembership(clubA);
        insertMembership(clubB);
        TenantTestContext.clear();
    }

    @AfterEach
    void removeTheSeededPerson() {
        jdbc.update("DELETE FROM t_person_club WHERE person_id = ?::uuid",
                personInBothClubs.toString());
        jdbc.update("DELETE FROM t_person WHERE id = ?::uuid", personInBothClubs.toString());
    }

    @Test
    void a_person_club_join_returns_only_the_reading_clubs_membership() {
        TenantTestContext.runAs(clubA, () -> {
            assertThat(clubIdsOfMembershipsReachedByAJoinFromPerson())
                    .as("Hibernate applies the @TenantId discriminator to PersonClub as a JOIN, "
                            + "not only as a query root — so no JPQL over PersonClub can compute "
                            + "a person's reach across the clubs of one deployment, and "
                            + "PersonClubMembershipOutsideTheTenantFilter exists for that read")
                    .containsExactly(clubA)
                    .doesNotContain(clubB);
            return null;
        });
    }

    @Test
    void the_tenant_filter_free_view_returns_every_clubs_membership() {
        TenantTestContext.runAs(clubA, () -> {
            assertThat(clubIdsOfMembershipsReachedByTheTenantFilterFreeView())
                    .as("the read-only view over t_person_club carries no @TenantId, so it sees "
                            + "every club's membership and can answer the deployment question")
                    .contains(clubA, clubB);
            return null;
        });
    }

    private List<UUID> clubIdsOfMembershipsReachedByAJoinFromPerson() {
        return entityManager.createQuery(
                        "select membership.clubId from Person p "
                                + "join PersonClub membership on membership.person = p "
                                + "where p.id = :personId", UUID.class)
                .setParameter("personId", personInBothClubs)
                .getResultList();
    }

    private List<UUID> clubIdsOfMembershipsReachedByTheTenantFilterFreeView() {
        return entityManager.createQuery(
                        "select reaching.clubId "
                                + "from PersonClubMembershipOutsideTheTenantFilter reaching "
                                + "where reaching.personId = :personId", UUID.class)
                .setParameter("personId", personInBothClubs)
                .getResultList();
    }

    private void insertMembership(UUID clubId) {
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                UUID.randomUUID().toString(), personInBothClubs.toString(), clubId.toString());
    }
}

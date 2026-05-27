package ch.alpenflight.persons.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sacred-cow regression guard for {@code Flight} crew load (S-058):
 * {@link Person} has no {@code @TenantId} / {@code @Filter} / {@code @Where},
 * so a PK load must return the row regardless of the caller's tenant scope.
 * A future refactor that added any of those would silently break multi-club
 * pilot rosters — this IT is the witness that catches the regression at
 * the per-aggregate layer.
 *
 * <p>Counterpart to the catalog-driven {@code LeakageSweepIT} (which proves
 * tenant-scoped rows are isolated); this IT proves the inverse for the one
 * documented cross-tenant aggregate root.
 */
class PersonsCrossTenantRideThroughIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-0000000000c3");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000000c4");
    private static final String NAME_PREFIX = "IT_PRT_";
    private static final String KEY_PREFIX = "IT_PR_";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PersonRepository persons;

    @BeforeEach
    void seed() {
        new TwoClubFixture(jdbc, CLUB_A, CLUB_B, NAME_PREFIX, KEY_PREFIX).seed();
        TenantTestContext.clear();
    }

    @Test
    void findById_returns_person_regardless_of_caller_tenant() {
        // Seed a Person attached only to CLUB_B.
        UUID personId = UUID.fromString("019e30c3-2c00-7001-8000-00000000cccd");
        UUID pcId = UUID.fromString("019e30c3-2c00-7001-8000-00000000ccce");
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "MultiClub", "Pilot");
        jdbc.update("INSERT INTO t_person_club (id, person_id, club_id) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid)",
                pcId.toString(), personId.toString(), CLUB_B.toString());

        // Caller acts as CLUB_A — a tenant that doesn't share any
        // membership with this Person. Flight.crew_id resolution in S-058
        // must still find the Person via PK regardless.
        TenantTestContext.runAs(CLUB_A, () -> {
            Optional<Person> found = persons.findActiveById(personId);
            assertThat(found)
                    .as("Person PK load is cross-tenant by design (sacred cow); "
                            + "no @TenantId / @Filter / @Where may scope it away")
                    .isPresent();
            assertThat(found.get().getFirstname()).isEqualTo("MultiClub");
        });

        // Same Person reachable under CLUB_B (the home tenant).
        TenantTestContext.runAs(CLUB_B, () ->
                assertThat(persons.findActiveById(personId)).isPresent());

        // And reachable outside any tenant context — sysadmin admin paths
        // load Persons via Tenants.runAs(null) / no @WithTenant.
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
                pcId.toString(), personId.toString(), CLUB_B.toString());

        TenantTestContext.runAs(CLUB_A, () ->
                assertThat(persons.findActiveListRowsInCurrentTenant())
                        .as("Per-tenant list JOINs through PersonClub; the @TenantId on the "
                                + "junction scopes the result away from foreign-tenant Persons")
                        .extracting(PersonRepository.ListRow::id)
                        .doesNotContain(personId));
    }
}

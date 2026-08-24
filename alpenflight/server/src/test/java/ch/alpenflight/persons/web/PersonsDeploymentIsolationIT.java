package ch.alpenflight.persons.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.persons.application.PersonDtos.PersonClubRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonCreateRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonLookupMatch;
import ch.alpenflight.persons.application.PersonDtos.PersonLookupRequest;
import ch.alpenflight.persons.application.PersonDtos.PersonResponse;
import ch.alpenflight.persons.application.PersonsService;
import ch.alpenflight.persons.domain.PersonNotFoundException;
import ch.alpenflight.platform.id.PersonId;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonsDeploymentIsolationIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_PDI_";
    private static final String KEY_PREFIX = "IT_PD";
    private static final LocalDate SHARED_BIRTHDAY = LocalDate.of(1979, 3, 14);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PersonsService personsService;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;
    @Autowired private DeploymentRepository deployments;

    private TwoClubFixture fixture;
    private UUID realClubA;
    private UUID realClubB;
    private UUID sandboxSeatClub;
    private UUID clubOfASecondRealDeployment;
    private UUID secondRealDeployment;

    private final List<UUID> personsThisTestWrote = new ArrayList<>();

    @BeforeEach
    void seed() {
        fixture = new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        realClubA = fixture.clubA();
        realClubB = fixture.clubB();
        sandboxSeatClub =
                fixture.seedAdditionalClubInDeployment(Deployment.SANDBOX_ID, "sandboxseat");
        secondRealDeployment = deployments.save(Deployment.startTrial(
                Clock.systemUTC(), NAME_PREFIX + "otherreal", UUID.randomUUID())).getId();
        clubOfASecondRealDeployment =
                fixture.seedAdditionalClubInDeployment(secondRealDeployment, "otherreal");
        TenantTestContext.clear();
    }

    @AfterEach
    void deleteThePersonsTheAdditionalClubsAndTheDeploymentThisTestSeeded() {
        deleteEveryPersonThisTestWroteAndTheMembershipsCascadingFromIt();
        fixture.deleteEveryAdditionalDeploymentClubThisFixtureSeeded();
        jdbc.update("DELETE FROM t_deployment WHERE id = ?", secondRealDeployment);
    }

    @Test
    void a_real_club_never_looks_up_a_member_of_a_sandbox_deployment_club() {
        String sandboxEmail = uniqueEmail("sandbox");
        PersonResponse sandboxMember = memberOf(sandboxSeatClub, "Sandbox", "Visitor", sandboxEmail);
        String realEmail = uniqueEmail("real");
        PersonResponse realMember = memberOf(realClubB, "Real", "Member", realEmail);

        TenantTestContext.runAs(realClubA, () -> {
            assertThat(personsService.lookup(new PersonLookupRequest(sandboxEmail, null, null, null))
                            .matches())
                    .as("a sandbox member must be absent from a real club's lookup")
                    .extracting(PersonLookupMatch::id)
                    .doesNotContain(sandboxMember.id());
            assertThat(personsService.lookup(new PersonLookupRequest(realEmail, null, null, null))
                            .matches())
                    .as("positive baseline — cross-club lookup stays open inside one deployment")
                    .extracting(PersonLookupMatch::id)
                    .contains(realMember.id());
            return null;
        });
    }

    @Test
    void a_sandbox_deployment_club_never_looks_up_a_member_of_a_real_club() {
        String realEmail = uniqueEmail("realtarget");
        PersonResponse realMember = memberOf(realClubA, "Real", "Target", realEmail);

        TenantTestContext.runAs(sandboxSeatClub, () -> {
            assertThat(personsService.lookup(new PersonLookupRequest(realEmail, null, null, null))
                            .matches())
                    .as("a real member must be absent from a demo visitor's lookup")
                    .extracting(PersonLookupMatch::id)
                    .doesNotContain(realMember.id());
            return null;
        });
    }

    @Test
    void a_sandbox_deployment_club_never_looks_up_a_real_member_by_identity_triple() {
        PersonResponse realMember =
                memberOf(realClubA, "Identity", "Triple", uniqueEmail("triple"));

        TenantTestContext.runAs(sandboxSeatClub, () -> {
            assertThat(personsService.lookup(
                            new PersonLookupRequest(null, "Identity", "Triple", SHARED_BIRTHDAY))
                            .matches())
                    .as("the identity triple must not reach across the deployment boundary either")
                    .extracting(PersonLookupMatch::id)
                    .doesNotContain(realMember.id());
            return null;
        });
    }

    @Test
    void a_sandbox_deployment_club_never_attaches_a_member_of_a_real_club() {
        PersonResponse realMember = memberOf(realClubA, "Attach", "Target", uniqueEmail("attach"));

        TenantTestContext.runAs(sandboxSeatClub, () -> {
            assertThatThrownBy(() ->
                    personsService.attachExistingPerson(realMember.id(), membership()))
                    .as("a demo visitor must not attach a real member into its sandbox club")
                    .isInstanceOf(PersonNotFoundException.class);
            return null;
        });
    }

    @Test
    void a_real_club_never_attaches_a_member_of_a_sandbox_deployment_club() {
        PersonResponse sandboxMember =
                memberOf(sandboxSeatClub, "Sandbox", "Attachee", uniqueEmail("sandboxattach"));

        TenantTestContext.runAs(realClubA, () -> {
            assertThatThrownBy(() ->
                    personsService.attachExistingPerson(sandboxMember.id(), membership()))
                    .as("a real club must not pull a sandbox member into its roster")
                    .isInstanceOf(PersonNotFoundException.class);
            return null;
        });
    }

    @Test
    void a_real_club_still_attaches_a_member_of_another_club_in_the_same_deployment() {
        PersonResponse memberOfB = memberOf(realClubB, "Same", "Deployment", uniqueEmail("same"));

        TenantTestContext.runAs(realClubA, () -> {
            PersonResponse attached =
                    personsService.attachExistingPerson(memberOfB.id(), membership());
            assertThat(attached.id())
                    .as("positive baseline — cross-club attach stays open inside one deployment")
                    .isEqualTo(memberOfB.id());
            return null;
        });
    }

    @Test
    void a_sandbox_seat_never_looks_up_a_person_that_holds_no_membership() {
        String orphanEmail = uniqueEmail("orphanlookup");
        PersonId orphan = orphanOf(realClubA, "Orphan", "Lookup", orphanEmail);

        TenantTestContext.runAs(sandboxSeatClub, () -> {
            assertThat(personsService.lookup(new PersonLookupRequest(orphanEmail, null, null, null))
                            .matches())
                    .as("a demo visitor must not read a person that holds no club membership")
                    .extracting(PersonLookupMatch::id)
                    .doesNotContain(orphan);
            assertThat(personsService.lookup(
                            new PersonLookupRequest(null, "Orphan", "Lookup", SHARED_BIRTHDAY))
                            .matches())
                    .as("the identity triple must not reach the membership-less person either")
                    .extracting(PersonLookupMatch::id)
                    .doesNotContain(orphan);
            return null;
        });
    }

    @Test
    void a_sandbox_seat_never_attaches_a_person_that_holds_no_membership() {
        PersonId orphan = orphanOf(realClubA, "Orphan", "Attach", uniqueEmail("orphanattach"));

        TenantTestContext.runAs(sandboxSeatClub, () -> {
            assertThatThrownBy(() -> personsService.attachExistingPerson(orphan, membership()))
                    .as("a demo visitor must not attach a person that holds no club membership")
                    .isInstanceOf(PersonNotFoundException.class);
            return null;
        });

        assertThat(aliveMembershipCountOf(orphan))
                .as("the refused attach writes no membership, so the person stays attachable "
                        + "for the real club that created it")
                .isZero();
    }

    @Test
    void a_sandbox_seat_creates_no_person_that_its_own_club_cannot_reach() {
        String refusedEmail = uniqueEmail("sandboxorphan");

        TenantTestContext.runAs(sandboxSeatClub, () -> {
            assertThatThrownBy(() -> personsService.createPerson(
                    personPayload("Sandbox", "Orphan", refusedEmail, null)))
                    .as("a membership-less person of a seat club survives the sandbox purge and "
                            + "reaches every real club, so the seat must not create one")
                    .isInstanceOf(IllegalArgumentException.class);
            return null;
        });

        assertThat(personRowCountOfEmail(refusedEmail))
                .as("the refused create writes no person row")
                .isZero();
    }

    @Test
    void a_club_of_a_second_real_deployment_never_looks_up_a_person_that_holds_no_membership() {
        String orphanEmail = uniqueEmail("crossrealorphan");
        PersonId orphan = orphanOf(realClubA, "Crossreal", "Orphan", orphanEmail);

        TenantTestContext.runAs(clubOfASecondRealDeployment, () -> {
            assertThat(personsService.lookup(new PersonLookupRequest(orphanEmail, null, null, null))
                            .matches())
                    .as("a second real deployment must not read the name, the birthday and the "
                            + "email of a membership-less person of another real deployment")
                    .extracting(PersonLookupMatch::id)
                    .doesNotContain(orphan);
            assertThat(personsService.lookup(
                            new PersonLookupRequest(null, "Crossreal", "Orphan", SHARED_BIRTHDAY))
                            .matches())
                    .as("the identity triple must not reach across the real deployment boundary "
                            + "either")
                    .extracting(PersonLookupMatch::id)
                    .doesNotContain(orphan);
            return null;
        });
    }

    @Test
    void a_real_club_still_attaches_a_person_that_holds_no_membership_through_its_id() {
        PersonId orphan = orphanOf(realClubA, "Orphan", "Person", uniqueEmail("orphan"));

        TenantTestContext.runAs(realClubB, () -> {
            PersonResponse attached = personsService.attachExistingPerson(orphan, membership());
            assertThat(attached.id())
                    .as("positive baseline — the create-then-attach flow keeps working, and a "
                            + "real club that holds the id still claims a migrated person that "
                            + "joined no club")
                    .isEqualTo(orphan);
            return null;
        });
    }

    private PersonResponse memberOf(UUID clubId, String firstname, String lastname, String email) {
        return recorded(TenantTestContext.runAs(clubId,
                () -> personsService.createPerson(
                        personPayload(firstname, lastname, email, membership()))));
    }

    private PersonId orphanOf(UUID clubId, String firstname, String lastname, String email) {
        return recorded(TenantTestContext.runAs(clubId,
                () -> personsService.createPerson(
                        personPayload(firstname, lastname, email, null)))).id();
    }

    private PersonResponse recorded(PersonResponse created) {
        personsThisTestWrote.add(created.id().value());
        return created;
    }

    private long aliveMembershipCountOf(PersonId personId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM t_person_club WHERE person_id = ? AND deleted_on IS NULL",
                Long.class, personId.value());
        return count == null ? 0L : count;
    }

    private long personRowCountOfEmail(String email) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM t_person WHERE email_private = ?", Long.class, email);
        return count == null ? 0L : count;
    }

    private void deleteEveryPersonThisTestWroteAndTheMembershipsCascadingFromIt() {
        if (personsThisTestWrote.isEmpty()) {
            return;
        }
        Object[] ids = personsThisTestWrote.stream().map(UUID::toString).toArray();
        String placeholders =
                String.join(", ", Collections.nCopies(personsThisTestWrote.size(), "?::uuid"));
        personsThisTestWrote.clear();
        jdbc.update("DELETE FROM t_person WHERE id IN (" + placeholders + ")", ids);
    }

    private static PersonCreateRequest personPayload(String firstname,
                                                     String lastname,
                                                     String email,
                                                     PersonClubRequest initialClubMembership) {
        return new PersonCreateRequest(
                firstname, lastname, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                email, null, false,
                SHARED_BIRTHDAY,
                null, initialClubMembership,
                null, false, false);
    }

    private static PersonClubRequest membership() {
        return new PersonClubRequest(null, null,
                false, false, false, false, false, false, false, false,
                false, false, false, true);
    }

    private static String uniqueEmail(String discriminator) {
        return "it-pdi-" + discriminator + "-"
                + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}

package ch.alpenflight.persons.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
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
import java.time.LocalDate;
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

    private TwoClubFixture fixture;
    private UUID realClubA;
    private UUID realClubB;
    private UUID sandboxSeatClub;

    @BeforeEach
    void seed() {
        fixture = new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        realClubA = fixture.clubA();
        realClubB = fixture.clubB();
        sandboxSeatClub =
                fixture.seedAdditionalClubInDeployment(Deployment.SANDBOX_ID, "sandboxseat");
        TenantTestContext.clear();
    }

    @AfterEach
    void deleteTheSandboxDeploymentClubsThisTestSeeded() {
        fixture.deleteEveryAdditionalDeploymentClubThisFixtureSeeded();
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
    void a_person_without_any_membership_belongs_to_no_deployment_and_stays_attachable() {
        PersonId orphan = TenantTestContext.runAs(realClubA,
                () -> personsService.createPerson(personPayload(
                        "Orphan", "Person", uniqueEmail("orphan"), null)).id());

        TenantTestContext.runAs(realClubA, () -> {
            PersonResponse attached = personsService.attachExistingPerson(orphan, membership());
            assertThat(attached.id())
                    .as("a person with no membership row crosses no deployment boundary, "
                            + "so the create-then-attach flow keeps working")
                    .isEqualTo(orphan);
            return null;
        });
    }

    private PersonResponse memberOf(UUID clubId, String firstname, String lastname, String email) {
        return TenantTestContext.runAs(clubId,
                () -> personsService.createPerson(
                        personPayload(firstname, lastname, email, membership())));
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

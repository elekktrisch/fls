package ch.alpenflight.publicregistration.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonClub;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import ch.alpenflight.publicregistration.application.PublicRegistrantDetails.InvoiceRecipient;
import ch.alpenflight.publicregistration.application.PublicRegistrationIntake.Accepted;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The registrant write both public flows share, driven through the production
 * intake so the {@code Tenants.runAs} window, the aggregate mutators and the
 * repository all take part. On trial:
 *
 * <ul>
 *   <li>the registrant Person + its PersonClub land in the club the slug named,
 *       carrying the contact fields legacy copies
 *       ({@code RegistrationService.cs:109-131});</li>
 *   <li><strong>both</strong> trainee markers move together — the person-level
 *       {@code has_glider_trainee_licence} licence AND the club-level
 *       {@code is_glider_trainee} role — true for discovery, false for scenic;</li>
 *   <li>the invoice Person exists only when the invoice address differs, is
 *       never a trainee, and carries the notification email as its private
 *       email ({@code :133-150});</li>
 *   <li>the registrant joins as a prospect: no member number, no member state,
 *       notifications off, inactive.</li>
 * </ul>
 *
 * <p>A member already exists in the target club throughout, so
 * {@code Person.joinClub}'s alive-membership dedupe is exercised on a populated
 * club rather than assumed: it keys on the (person, club) pair, so a stranger's
 * membership must neither collide with the registrant's nor be mutated by it.
 */
class PublicRegistrantWriteIT extends PostgresIntegrationTest {

    private static final LocalDate DISCOVERY_DAY = LocalDate.of(2099, 6, 15);

    @Autowired PublicRegistrationIntake intake;
    @Autowired PersonRepository persons;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private UUID clubId;
    private String slug;
    private UUID existingMemberId;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_PRW_", "IT_PRW_");
        fixture.seed();
        clubId = fixture.clubA();

        Club club = clubs.findActiveById(clubId).orElseThrow();
        club.enablePublicRegistration();
        clubs.save(club);
        slug = Objects.requireNonNull(club.getSlug(), "fixture club has no slug");

        existingMemberId = TenantTestContext.runAs(clubId, () -> {
            Person member = Person.register("Vreni", "Vorbestand", null);
            member.joinClub(clubId, "M-1", null, PersonRoleFlags.none(),
                    PersonNotificationPrefs.none(), true);
            Person saved = persons.save(member);
            persons.flush();
            return Objects.requireNonNull(saved.getId()).value();
        });
    }

    @Test
    void a_discovery_registration_creates_a_trainee_registrant_and_an_invoice_person() {
        long personsBefore = countPersons();

        Accepted accepted = intake.acceptDiscovery(
                slug, "198.51.100.11", registrant(false), DISCOVERY_DAY);

        assertThat(countPersons()).isEqualTo(personsBefore + 2);
        assertThat(accepted.club().clubId()).isEqualTo(clubId);

        withPerson(accepted.registered().registrantPersonId(), person -> {
            assertThat(person.getFirstname()).isEqualTo("Rosa");
            assertThat(person.getLastname()).isEqualTo("Renggli");
            assertThat(person.getAddressLine1()).isEqualTo("Flugplatzstrasse 7");
            assertThat(person.getZip()).isEqualTo("6060");
            assertThat(person.getCity()).isEqualTo("Sarnen");
            assertThat(person.getPrivatePhone()).isEqualTo("041 660 11 22");
            assertThat(person.getBusinessPhone()).isEqualTo("041 660 33 44");
            assertThat(person.getMobilePhone()).isEqualTo("079 555 66 77");
            assertThat(person.getEmailPrivate()).isEqualTo("rosa.renggli@example.ch");
            assertThat(person.hasGliderTraineeLicence()).isTrue();

            PersonClub membership = onlyMembership(person);
            assertThat(membership.getClubId()).isEqualTo(clubId);
            assertThat(membership.isGliderTrainee()).isTrue();
            assertThat(membership.getMemberNumber()).isNull();
            assertThat(membership.getMemberStateId()).isNull();
            assertThat(membership.isActive()).isFalse();
            assertThat(membership.isReceiveFlightReports()).isFalse();
            assertThat(membership.isReceiveAircraftReservationNotifications()).isFalse();
            assertThat(membership.isReceivePlanningDayRoleReminder()).isFalse();
        });

        UUID invoiceId = accepted.registered().invoicePersonId();
        assertThat(invoiceId).isNotNull();
        withPerson(Objects.requireNonNull(invoiceId), person -> {
            assertThat(person.getFirstname()).isEqualTo("Beat");
            assertThat(person.getLastname()).isEqualTo("Bezahler");
            assertThat(person.getAddressLine1()).isEqualTo("Buchhaltungsweg 3");
            assertThat(person.getZip()).isEqualTo("6003");
            assertThat(person.getCity()).isEqualTo("Luzern");
            assertThat(person.getEmailPrivate()).isEqualTo("beat.bezahler@example.ch");
            assertThat(person.getMobilePhone()).isNull();
            assertThat(person.getPrivatePhone()).isNull();
            assertThat(person.getBusinessPhone()).isNull();
            assertThat(person.hasGliderTraineeLicence()).isFalse();

            PersonClub membership = onlyMembership(person);
            assertThat(membership.getClubId()).isEqualTo(clubId);
            assertThat(membership.isGliderTrainee()).isFalse();
            assertThat(membership.isActive()).isFalse();
        });

        // joinClub deduplicates per (person, club) pair: the stranger who was
        // already a member of this club keeps exactly the membership the fixture
        // gave them, and the registrant gets their own alongside it.
        withPerson(existingMemberId, person -> {
            PersonClub membership = onlyMembership(person);
            assertThat(membership.getMemberNumber()).isEqualTo("M-1");
            assertThat(membership.isActive()).isTrue();
        });
    }

    @Test
    void no_invoice_person_is_created_when_the_invoice_address_is_the_same() {
        long personsBefore = countPersons();

        Accepted accepted = intake.acceptDiscovery(
                slug, "198.51.100.12", registrant(true), DISCOVERY_DAY);

        assertThat(accepted.registered().invoicePersonId()).isNull();
        assertThat(countPersons()).isEqualTo(personsBefore + 1);
        assertThat(countPersonsNamed("Bezahler")).isZero();
    }

    @Test
    void a_scenic_registration_leaves_both_trainee_markers_false() {
        Accepted accepted = intake.acceptScenic(slug, "198.51.100.13", registrant(true));

        withPerson(accepted.registered().registrantPersonId(), person -> {
            assertThat(person.hasGliderTraineeLicence()).isFalse();
            assertThat(onlyMembership(person).isGliderTrainee()).isFalse();
        });
    }

    private static PublicRegistrantDetails registrant(boolean invoiceAddressIsSame) {
        return new PublicRegistrantDetails(
                "Rosa", "Renggli", "Flugplatzstrasse 7", "6060", "Sarnen", null,
                "041 660 11 22", "041 660 33 44", "079 555 66 77", "rosa.renggli@example.ch",
                invoiceAddressIsSame,
                invoiceAddressIsSame ? null : new InvoiceRecipient(
                        "Beat", "Bezahler", "Buchhaltungsweg 3", "6003", "Luzern", null,
                        "beat.bezahler@example.ch"));
    }

    /**
     * Reads a Person back inside a tenant window AND a transaction — the lazy
     * {@code personClubs} collection is only reachable while the session is
     * open, and only carries the tenant's rows while the carrier is set.
     */
    private void withPerson(UUID personId, Consumer<Person> assertions) {
        TenantTestContext.runAs(clubId, () -> new TransactionTemplate(txManager).executeWithoutResult(
                status -> assertions.accept(persons.findActiveById(personId).orElseThrow())));
    }

    private static PersonClub onlyMembership(Person person) {
        assertThat(person.getActivePersonClubs()).hasSize(1);
        return person.getActivePersonClubs().getFirst();
    }

    private long countPersons() {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM t_person", Long.class);
        return rows == null ? 0L : rows;
    }

    /**
     * Scoped to this test's club: {@code t_person} is cross-tenant and the
     * fixture's teardown only reaches tenant-scoped tables, so a global count
     * would also see registrants left by earlier runs and other suites.
     */
    private long countPersonsNamed(String lastname) {
        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM t_person p JOIN t_person_club pc ON pc.person_id = p.id "
                        + "WHERE p.lastname = ? AND pc.club_id = ?::uuid",
                Long.class, lastname, clubId.toString());
        return rows == null ? 0L : rows;
    }
}

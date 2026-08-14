package ch.alpenflight.publicregistration.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
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

class PublicRegistrantWriteIT extends PostgresIntegrationTest {

    private static final LocalDate DISCOVERY_DAY = LocalDate.of(2099, 6, 15);

    @Autowired PublicRegistrationIntake intake;
    @Autowired PersonRepository persons;
    @Autowired ClubRepository clubs;
    @Autowired DiscoveryFlightDayRepository discoveryDays;
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
        publishDiscoveryDay(DISCOVERY_DAY);

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
        assertRegistrant(accepted, true);
        assertInvoiceRecipient(accepted);

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
    void a_scenic_registration_writes_the_same_shape_with_both_trainee_markers_false() {
        long personsBefore = countPersons();

        Accepted accepted = intake.acceptScenic(slug, "198.51.100.13", registrant(false));

        assertThat(countPersons()).isEqualTo(personsBefore + 2);
        assertThat(accepted.club().clubId()).isEqualTo(clubId);
        assertRegistrant(accepted, false);
        assertInvoiceRecipient(accepted);
        assertThat(accepted.reservation())
                .as("the scenic form has no day selection, so there is nothing to book")
                .isNull();
    }

    @Test
    void no_invoice_person_is_created_for_a_scenic_registration_with_the_same_address() {
        long personsBefore = countPersons();

        Accepted accepted = intake.acceptScenic(slug, "198.51.100.14", registrant(true));

        assertThat(accepted.registered().invoicePersonId()).isNull();
        assertThat(countPersons()).isEqualTo(personsBefore + 1);
        assertThat(countPersonsNamed("Bezahler")).isZero();
    }

    private void assertRegistrant(Accepted accepted, boolean expectTrainee) {
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
            assertThat(person.hasGliderTraineeLicence()).isEqualTo(expectTrainee);

            PersonClub membership = onlyMembership(person);
            assertThat(membership.getClubId()).isEqualTo(clubId);
            assertThat(membership.isGliderTrainee()).isEqualTo(expectTrainee);
            assertThat(membership.getMemberNumber()).isNull();
            assertThat(membership.getMemberStateId()).isNull();
            assertThat(membership.isActive()).isFalse();
            assertThat(membership.isReceiveFlightReports()).isFalse();
            assertThat(membership.isReceiveAircraftReservationNotifications()).isFalse();
            assertThat(membership.isReceivePlanningDayRoleReminder()).isFalse();
        });
    }

    private void assertInvoiceRecipient(Accepted accepted) {
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
    }

    private static PublicRegistrantDetails registrant(boolean invoiceAddressIsSame) {
        return new PublicRegistrantDetails(
                "Rosa", "Renggli", "Flugplatzstrasse 7", "6060", "Sarnen", null,
                "041 660 11 22", "041 660 33 44", "079 555 66 77", "rosa.renggli@example.ch",
                null, invoiceAddressIsSame, false,
                invoiceAddressIsSame ? null : new InvoiceRecipient(
                        "Beat", "Bezahler", "Buchhaltungsweg 3", "6003", "Luzern", null,
                        "beat.bezahler@example.ch"));
    }

    private void publishDiscoveryDay(LocalDate eventDate) {
        TenantTestContext.runAs(clubId, () ->
                discoveryDays.save(DiscoveryFlightDay.schedule(eventDate, eventDate)));
    }

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

    private long countPersonsNamed(String lastname) {
        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM t_person p JOIN t_person_club pc ON pc.person_id = p.id "
                        + "WHERE p.lastname = ? AND pc.club_id = ?::uuid",
                Long.class, lastname, clubId.toString());
        return rows == null ? 0L : rows;
    }
}

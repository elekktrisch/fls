package ch.alpenflight.publicregistration.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import ch.alpenflight.publicregistration.application.PublicClubResolver.PublicClub;
import ch.alpenflight.publicregistration.application.PublicRegistrantDetails.InvoiceRecipient;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PublicRegistrationTxWriter {

    private static final String AUDIT_ENTITY_TYPE = "PublicFlightRegistration";

    private final AuditTrail auditTrail;
    private final PersonRepository persons;
    private final DiscoveryReservationBooker reservationBooker;

    PublicRegistrationTxWriter(AuditTrail auditTrail, PersonRepository persons,
            DiscoveryReservationBooker reservationBooker) {
        this.auditTrail = auditTrail;
        this.persons = persons;
        this.reservationBooker = reservationBooker;
    }

    @Transactional
    public RegisteredPersons registerScenic(PublicClub club, PublicRegistrantDetails details,
            String submitterClientIp) {
        PublicRegistrationKind kind = PublicRegistrationKind.SCENIC_FLIGHT;
        RegisteredPersons registered = writeRegistrants(club.clubId(), kind, details);
        audit(club, kind, submitterClientIp);
        return registered;
    }

    @Transactional
    public DiscoveryRegistration registerDiscovery(PublicClub club,
            PublicRegistrantDetails details, LocalDate selectedDay, String submitterClientIp) {
        PublicRegistrationKind kind = PublicRegistrationKind.DISCOVERY_FLIGHT;
        RegisteredPersons registered = writeRegistrants(club.clubId(), kind, details);
        DiscoveryReservationOutcome reservation = reservationBooker.blockDoubleSeaterAllDayDeliberatelyOverlappingOtherCandidates(
                club.clubId(), registered.registrantPersonId(), selectedDay);
        audit(club, kind, submitterClientIp);
        return new DiscoveryRegistration(registered, reservation);
    }

    private RegisteredPersons writeRegistrants(UUID clubId,
            PublicRegistrationKind kind, PublicRegistrantDetails details) {
        UUID registrantId = createRegistrant(clubId, kind, details);
        InvoiceRecipient invoice = details.invoiceRecipient();
        UUID invoiceId = invoice == null ? null : createInvoiceRecipient(clubId, invoice);
        persons.flush();
        return new RegisteredPersons(registrantId, invoiceId);
    }

    private UUID createRegistrant(UUID clubId,
            PublicRegistrationKind kind, PublicRegistrantDetails details) {
        boolean gliderTraineeOnPersonAndMembership = kind.marksGliderTrainee();
        Person person = Person.register(details.firstname(), details.lastname(), null);
        person.updateContact(
                details.addressLine1(), null, details.zip(), details.city(), null,
                details.countryId(),
                details.privatePhone(), details.mobilePhone(), details.businessPhone(), null,
                details.privateEmail(), null, false, null, null, false);
        person.updateLicences(
                false, false, false, false, gliderTraineeOnPersonAndMembership,
                false, false, false, false, false,
                null, null, null, null, null, null, null,
                false, false, false, false);
        joinTargetClubAsInactiveProspect(person, clubId, gliderTraineeOnPersonAndMembership);
        return idOf(persons.save(person));
    }

    private UUID createInvoiceRecipient(UUID clubId, InvoiceRecipient invoice) {
        Person person = Person.register(invoice.firstname(), invoice.lastname(), null);
        person.updateContact(
                invoice.addressLine1(), null, invoice.zip(), invoice.city(), null,
                invoice.countryId(),
                null, null, null, null,
                invoice.notificationEmail(), null, false, null, null, false);
        joinTargetClubAsInactiveProspect(person, clubId, false);
        return idOf(persons.save(person));
    }

    private static void joinTargetClubAsInactiveProspect(Person person, UUID clubId,
            boolean gliderTrainee) {
        PersonRoleFlags roles = new PersonRoleFlags(
                false, false, false, false, gliderTrainee, false, false, false);
        person.joinClub(clubId, null, null, roles, PersonNotificationPrefs.none(), false);
    }

    private void audit(PublicClub club, PublicRegistrationKind kind, String submitterClientIp) {
        auditTrail.recordAnonymousPublicSubmission(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, club.clubId(),
                        new AcceptedRegistration(kind, club.clubId())),
                submitterClientIp);
    }

    private static UUID idOf(Person saved) {
        if (saved.getId() == null) {
            throw new IllegalStateException("Saved Person has no id");
        }
        return saved.getId().value();
    }

    public record AcceptedRegistration(PublicRegistrationKind kind, UUID clubId) {}

    public record RegisteredPersons(UUID registrantPersonId, @Nullable UUID invoicePersonId) {}

    public record DiscoveryRegistration(RegisteredPersons persons,
                                        DiscoveryReservationOutcome reservation) {}
}

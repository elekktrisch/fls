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

/**
 * The tenant-scoped transactional unit for an accepted public registration. A
 * separate bean so the {@code @Transactional} boundary nests INSIDE
 * {@link ch.alpenflight.platform.tenancy.Tenants#runAs}: the tenant carrier must
 * stay set across open, flush AND commit, and self-invocation would not apply
 * the proxy advice. Audit emission lives here so the AFTER_COMMIT event carries
 * the same tenant as the write.
 *
 * <p>The actor resolves to anonymous — no {@code JwtAuthenticationToken} in the
 * security context — so the row lands with {@code system_actor=true} and both
 * actor identifiers null, scoped to the club the URL named.
 *
 * <h2>What a registrant becomes</h2>
 *
 * <p>An anonymous registrant is a prospect, not a member. The membership is
 * therefore opened with no member number, no member state, notification
 * preferences all off and {@code active = false} — see
 * {@link #joinTargetClub}.
 */
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
    public void recordAccepted(PublicClub club, PublicRegistrationKind kind) {
        audit(club, kind);
    }

    /**
     * The scenic flow: the shared registrant write and nothing else. The kind is
     * fixed here rather than passed in, so no caller can route a discovery
     * submission down a path that silently books no slot.
     */
    @Transactional
    public RegisteredPersons registerScenic(PublicClub club, PublicRegistrantDetails details) {
        PublicRegistrationKind kind = PublicRegistrationKind.SCENIC_FLIGHT;
        RegisteredPersons registered = writeRegistrants(club.clubId(), kind, details);
        audit(club, kind);
        return registered;
    }

    /**
     * The discovery flow: the same registrant write, then the candidate's all-day
     * glider slot on the day they picked. The booking is best-effort — a club
     * without a double-seater or without a homebase still gets the registration,
     * and the organiser mail carries the reason (see
     * {@link DiscoveryReservationOutcome}).
     */
    @Transactional
    public DiscoveryRegistration registerDiscovery(PublicClub club,
            PublicRegistrantDetails details, LocalDate selectedDay) {
        PublicRegistrationKind kind = PublicRegistrationKind.DISCOVERY_FLIGHT;
        RegisteredPersons registered = writeRegistrants(club.clubId(), kind, details);
        DiscoveryReservationOutcome reservation = reservationBooker.book(
                club.clubId(), registered.registrantPersonId(), selectedDay);
        audit(club, kind);
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
        boolean trainee = kind.marksGliderTrainee();
        Person person = Person.register(details.firstname(), details.lastname(), null);
        person.updateContact(
                details.addressLine1(), null, details.zip(), details.city(), null,
                details.countryId(),
                details.privatePhone(), details.mobilePhone(), details.businessPhone(), null,
                details.privateEmail(), null, false, null, null, false);
        person.updateLicences(
                false, false, false, false, trainee, false, false, false, false, false,
                null, null, null, null, null, null, null,
                false, false, false, false);
        joinTargetClub(person, clubId, trainee);
        return idOf(persons.save(person));
    }

    private UUID createInvoiceRecipient(UUID clubId, InvoiceRecipient invoice) {
        Person person = Person.register(invoice.firstname(), invoice.lastname(), null);
        person.updateContact(
                invoice.addressLine1(), null, invoice.zip(), invoice.city(), null,
                invoice.countryId(),
                null, null, null, null,
                invoice.notificationEmail(), null, false, null, null, false);
        joinTargetClub(person, clubId, false);
        return idOf(persons.save(person));
    }

    /**
     * Opens the club membership an anonymous registrant gets. Every optional
     * argument is deliberately empty:
     *
     * <ul>
     *   <li>no member number / member state — the club assigns those when it
     *       decides to take the prospect on, and inventing one here would put a
     *       fabricated identity in the members list;</li>
     *   <li>notification preferences all off — {@code receiveFlightReports}
     *       would enrol a stranger who filled in a web form into the club's
     *       nightly flight-report mail ({@code DailyReportJob:197});</li>
     *   <li>{@code active = false} — the membership exists so the flight can be
     *       booked and billed against a real person, not because a member
     *       joined.</li>
     * </ul>
     *
     * <p>This matches legacy, which added a {@code PersonClub} carrying nothing
     * but the club id and the trainee role ({@code RegistrationService.cs:124-128}).
     */
    private static void joinTargetClub(Person person, UUID clubId, boolean gliderTrainee) {
        PersonRoleFlags roles = new PersonRoleFlags(
                false, false, false, false, gliderTrainee, false, false, false);
        person.joinClub(clubId, null, null, roles, PersonNotificationPrefs.none(), false);
    }

    private void audit(PublicClub club, PublicRegistrationKind kind) {
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, club.clubId(),
                        new AcceptedRegistration(kind, club.clubId())));
    }

    private static UUID idOf(Person saved) {
        if (saved.getId() == null) {
            throw new IllegalStateException("Saved Person has no id");
        }
        return saved.getId().value();
    }

    /** Non-PII audit snapshot: which flow, which club. */
    public record AcceptedRegistration(PublicRegistrationKind kind, UUID clubId) {}

    /** The Persons an accepted submission created. */
    public record RegisteredPersons(UUID registrantPersonId, @Nullable UUID invoicePersonId) {}

    /** What a discovery submission wrote: the Persons plus the reservation attempt. */
    public record DiscoveryRegistration(RegisteredPersons persons,
                                        DiscoveryReservationOutcome reservation) {}
}

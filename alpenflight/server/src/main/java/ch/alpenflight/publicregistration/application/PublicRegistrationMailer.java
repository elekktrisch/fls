package ch.alpenflight.publicregistration.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.publicregistration.application.PublicClubResolver.PublicClub;
import ch.alpenflight.publicregistration.application.PublicRegistrantDetails.InvoiceRecipient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The two notification mails an accepted public registration sends: one
 * confirmation to the registrant, one notification to the club's organiser
 * address.
 *
 * <h2>Both mails are optional, the registration is not</h2>
 *
 * <p>A registrant who left the email field empty gets no confirmation, and a
 * club with no organiser address gets no notification — in both cases the
 * registration still stands ({@code RegistrationService.cs:222-255}, mirrored
 * for scenic at {@code :369-398}). Everything here is therefore best-effort and
 * post-commit: a mail or directory failure is logged, never propagated, because
 * a 500 over a committed registration invites a duplicate resubmission.
 *
 * <h2>Tenant window</h2>
 *
 * <p>Called from inside {@link PublicRegistrationIntake}'s
 * {@code Tenants.runAs} window. The club's {@code t_email_template} override is
 * looked up {@code @TenantId}-scoped by {@code EmailTemplateDbResolver}, so a
 * send outside the window silently falls back to the shipped file default.
 *
 * <h2>One model, four templates</h2>
 *
 * <p>All four templates bind the same flat model built from the submission.
 * Legacy's two scenic templates interpolate a {@code TrialFlightRegistrationModel}
 * namespace their send path never puts in the token dictionary
 * ({@code RegistrationEmailBuildService.cs:211-213,271-273}), so Velocity's
 * silent {@code $!} rendered email, all three phone numbers and the remarks
 * blank in every scenic mail legacy ever sent. A single shared model removes the
 * class of bug rather than fixing two strings.
 */
@Component
class PublicRegistrationMailer {

    static final String TEMPLATE_DISCOVERY_CANDIDATE = "discovery-registration-candidate";
    static final String TEMPLATE_DISCOVERY_ORGANISER = "discovery-registration-organiser";
    static final String TEMPLATE_SCENIC_CANDIDATE = "scenic-registration-candidate";
    static final String TEMPLATE_SCENIC_ORGANISER = "scenic-registration-organiser";

    static final String SUBJECT_DISCOVERY_CANDIDATE = "Anmeldung Schnupperflug erhalten";
    static final String SUBJECT_DISCOVERY_ORGANISER = "Neue Anmeldung Schnupperflug";
    static final String SUBJECT_SCENIC_CANDIDATE = "Anmeldung Mitflug erhalten";
    static final String SUBJECT_SCENIC_ORGANISER = "Neue Anmeldung Mitflug";

    /** Legacy tells the candidate they hear back two days before the day. */
    private static final int CONTACT_LEAD_DAYS = 2;

    private static final DateTimeFormatter DAY_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN);

    private static final Logger LOG = LoggerFactory.getLogger(PublicRegistrationMailer.class);

    private final TemplatedMailService mail;
    private final ClubRepository clubs;
    private final LocationRepository locations;

    PublicRegistrationMailer(TemplatedMailService mail, ClubRepository clubs,
            LocationRepository locations) {
        this.mail = mail;
        this.clubs = clubs;
        this.locations = locations;
    }

    void sendDiscovery(PublicClub club, PublicRegistrantDetails registrant,
            LocalDate selectedDay, DiscoveryReservationOutcome reservation) {
        try {
            Club aggregate = requireClub(club.clubId());
            Map<String, Object> model = model(club, registrant);
            model.put("locationName", homebaseName(aggregate));
            model.put("flightDate", DAY_FORMAT.format(selectedDay));
            model.put("contactDate", DAY_FORMAT.format(selectedDay.minusDays(CONTACT_LEAD_DAYS)));

            mailCandidate(registrant, SUBJECT_DISCOVERY_CANDIDATE,
                    TEMPLATE_DISCOVERY_CANDIDATE, model);

            if (aggregate.notifiesDiscoveryFlightOperator()) {
                model.put("reservationMessageKey", reservation.messageKey());
                mail.send(recipients(aggregate.getDiscoveryFlightOperatorEmail()),
                        SUBJECT_DISCOVERY_ORGANISER, TEMPLATE_DISCOVERY_ORGANISER, model);
            }
        } catch (RuntimeException e) {
            LOG.error("Discovery-flight registration mails for club {} failed", club.clubId(), e);
        }
    }

    void sendScenic(PublicClub club, PublicRegistrantDetails registrant) {
        try {
            Club aggregate = requireClub(club.clubId());
            Map<String, Object> model = model(club, registrant);

            mailCandidate(registrant, SUBJECT_SCENIC_CANDIDATE, TEMPLATE_SCENIC_CANDIDATE, model);

            if (aggregate.notifiesScenicFlightOperator()) {
                mail.send(recipients(aggregate.getScenicFlightOperatorEmail()),
                        SUBJECT_SCENIC_ORGANISER, TEMPLATE_SCENIC_ORGANISER, model);
            }
        } catch (RuntimeException e) {
            LOG.error("Scenic-flight registration mails for club {} failed", club.clubId(), e);
        }
    }

    private void mailCandidate(PublicRegistrantDetails registrant, String subject,
            String template, Map<String, Object> model) {
        String recipient = candidateRecipient(registrant);
        if (recipient == null) {
            LOG.info("Registration carries no candidate email address — {} not sent", template);
            return;
        }
        mail.send(recipient, subject, template, model);
    }

    /**
     * Legacy's recipient branch: the registrant's own address when the invoice
     * address is theirs, otherwise the invoice block's notification address.
     * Null means the submission named neither — the registrant reached the
     * contact-channel invariant on a phone number alone.
     */
    private static @Nullable String candidateRecipient(PublicRegistrantDetails registrant) {
        if (registrant.invoiceAddressIsSame()) {
            return registrant.privateEmail();
        }
        InvoiceRecipient invoice = registrant.invoiceRecipient();
        return invoice == null ? null : invoice.notificationEmail();
    }

    private static Map<String, Object> model(PublicClub club, PublicRegistrantDetails registrant) {
        InvoiceRecipient invoice = registrant.invoiceRecipient();
        String candidateName = registrant.firstname() + " " + registrant.lastname();
        String invoiceName = invoice == null
                ? candidateName
                : invoice.firstname() + " " + invoice.lastname();
        Map<String, Object> model = new HashMap<>();
        model.put("clubName", club.clubName());
        model.put("locationName", "");
        model.put("flightDate", "");
        model.put("contactDate", "");
        model.put("reservationMessageKey", "");
        model.put("candidateName", candidateName);
        model.put("addressLine1", registrant.addressLine1());
        model.put("zip", registrant.zip());
        model.put("city", registrant.city());
        model.put("privateEmail", orEmpty(registrant.privateEmail()));
        model.put("mobilePhone", orEmpty(registrant.mobilePhone()));
        model.put("privatePhone", orEmpty(registrant.privatePhone()));
        model.put("businessPhone", orEmpty(registrant.businessPhone()));
        model.put("remarks", orEmpty(registrant.remarks()));
        model.put("invoiceName", invoiceName);
        model.put("invoiceAddressLine1",
                invoice == null ? registrant.addressLine1() : invoice.addressLine1());
        model.put("invoiceZip", invoice == null ? registrant.zip() : invoice.zip());
        model.put("invoiceCity", invoice == null ? registrant.city() : invoice.city());
        // Legacy ships a role label ("Rechnungs-Empfänger" / "Schnupperflug-
        // Kandidat"); the name says the same thing and spares the organiser the
        // cross-reference.
        model.put("couponRecipientName",
                registrant.sendCouponToInvoiceAddress() ? invoiceName : candidateName);
        return model;
    }

    private Club requireClub(UUID clubId) {
        return clubs.findActiveById(clubId).orElseThrow(() -> new IllegalStateException(
                "Registration resolved a club that no longer exists: " + clubId));
    }

    private String homebaseName(Club club) {
        UUID homebaseId = club.getHomebaseId();
        if (homebaseId == null) {
            return "";
        }
        return locations.findActiveById(homebaseId)
                .map(Location::getLocationName)
                .orElse("");
    }

    /** {@code Club} stores the organiser opt-in comma-joined (Club.java:295-299). */
    private static List<String> recipients(@Nullable String commaJoined) {
        if (commaJoined == null) {
            return List.of();
        }
        return Arrays.stream(commaJoined.split(","))
                .map(String::strip)
                .filter(address -> !address.isEmpty())
                .toList();
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}

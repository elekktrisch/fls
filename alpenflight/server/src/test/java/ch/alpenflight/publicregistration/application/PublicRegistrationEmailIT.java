package ch.alpenflight.publicregistration.application;

import static ch.alpenflight.publicregistration.application.PublicRegistrationMailer.SUBJECT_DISCOVERY_CANDIDATE;
import static ch.alpenflight.publicregistration.application.PublicRegistrationMailer.SUBJECT_DISCOVERY_ORGANISER;
import static ch.alpenflight.publicregistration.application.PublicRegistrationMailer.SUBJECT_SCENIC_CANDIDATE;
import static ch.alpenflight.publicregistration.application.PublicRegistrationMailer.SUBJECT_SCENIC_ORGANISER;
import static ch.alpenflight.publicregistration.application.PublicRegistrationMailer.TEMPLATE_DISCOVERY_ORGANISER;
import static ch.alpenflight.publicregistration.application.PublicRegistrationMailer.TEMPLATE_SCENIC_CANDIDATE;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import ch.alpenflight.emailtemplates.application.EmailTemplateCatalog;
import ch.alpenflight.emailtemplates.domain.EmailTemplate;
import ch.alpenflight.emailtemplates.domain.EmailTemplateRepository;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.platform.mail.CapturedMailSender;
import ch.alpenflight.platform.mail.MailMessage;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.publicregistration.application.DiscoveryReservationOutcome.Status;
import ch.alpenflight.publicregistration.application.PublicRegistrantDetails.InvoiceRecipient;
import ch.alpenflight.publicregistration.application.PublicRegistrationIntake.Accepted;
import ch.alpenflight.referencedata.domain.AircraftType;
import ch.alpenflight.referencedata.domain.AircraftTypeRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.referencedata.domain.LocationTypeRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The four registration mails, driven through the production intake so the
 * recipient branch, the render and the {@code Tenants.runAs} window all take
 * part ({@code RegistrationService.cs:222-255} and {@code :369-398}).
 *
 * <p>The load-bearing cases:
 *
 * <ul>
 *   <li>{@link #a_scenic_registration_renders_the_contact_fields_legacy_left_blank}
 *       — legacy's scenic templates interpolate a namespace their send path never
 *       binds, so email, all three phones and the remarks render blank in every
 *       scenic mail legacy has sent. An assertion that the mail merely arrives
 *       passes against that bug; these assert the values.</li>
 *   <li>{@link #a_registrant_without_an_email_address_is_still_registered} and
 *       {@link #a_club_without_an_organiser_address_still_takes_the_registration}
 *       — the two no-mail branches. Each asserts the OTHER mail did go out, so a
 *       silently broken send path cannot pass them by sending nothing at all.</li>
 *   <li>{@link #a_club_override_wins_over_the_shipped_template} — the override
 *       lookup is {@code @TenantId}-scoped, so it resolves only if the send
 *       happens inside the tenant window.</li>
 * </ul>
 */
@Import(CapturedMailSender.Config.class)
class PublicRegistrationEmailIT extends PostgresIntegrationTest {

    private static final LocalDate DISCOVERY_DAY = LocalDate.of(2099, 6, 15);
    private static final String ORGANISER_LIST = "buero@example.ch; schnupper@example.ch";
    private static final List<String> ORGANISERS =
            List.of("buero@example.ch", "schnupper@example.ch");
    private static final String REMARKS = "Fliegt zum ersten Mal.";
    private static final String HOMEBASE = "IT_PRM_Heimflugplatz";

    @Autowired PublicRegistrationIntake intake;
    @Autowired TemplatedMailService mail;
    @Autowired EmailTemplateRepository overrides;
    @Autowired CapturedMailSender outbox;
    @Autowired ClubRepository clubs;
    @Autowired DiscoveryFlightDayRepository discoveryDays;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired LocationTypeRepository locationTypes;
    @Autowired LocationRepository locations;
    @Autowired AircraftRepository aircraft;
    @Autowired AircraftTypeRepository aircraftTypes;
    @Autowired JdbcTemplate jdbc;

    private UUID clubId;
    private String slug;
    private String clubName;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_PRM_", "IT_PRM_");
        fixture.seed();
        clubId = fixture.clubA();

        Club club = clubs.findActiveById(clubId).orElseThrow();
        club.enablePublicRegistration();
        club.setRegistrationOperatorEmails(ORGANISER_LIST, ORGANISER_LIST);
        clubs.save(club);
        slug = Objects.requireNonNull(club.getSlug(), "fixture club has no slug");
        clubName = club.getClubname();
        publishDiscoveryDay(DISCOVERY_DAY);

        outbox.clear();
    }

    @Test
    void a_discovery_registration_mails_the_candidate_and_the_organiser_list() {
        giveClubAHomebase();
        giveClubADoubleSeaterGlider();

        intake.acceptDiscovery(slug, "198.51.100.41", registrant(true), DISCOVERY_DAY);

        MailMessage candidate = only(SUBJECT_DISCOVERY_CANDIDATE);
        assertThat(candidate.to()).containsExactly("rosa.renggli@example.ch");
        assertThat(candidate.htmlBody())
                .contains("15.06.2099")
                .contains(HOMEBASE)
                .contains(clubName)
                .contains("13.06.2099");
        assertCandidateFieldsRendered(candidate);

        MailMessage organiser = only(SUBJECT_DISCOVERY_ORGANISER);
        assertThat(organiser.to()).containsExactlyElementsOf(ORGANISERS);
        assertThat(organiser.htmlBody()).contains("Reservation erstellt.");
        assertCandidateFieldsRendered(organiser);
    }

    @Test
    void a_skipped_reservation_is_reported_to_the_organiser() {
        giveClubAHomebase();

        Accepted accepted =
                intake.acceptDiscovery(slug, "198.51.100.42", registrant(true), DISCOVERY_DAY);

        assertThat(Objects.requireNonNull(accepted.reservation()).status())
                .isEqualTo(Status.SKIPPED_NO_DOUBLE_SEATER);
        assertThat(only(SUBJECT_DISCOVERY_ORGANISER).htmlBody())
                .contains("Keine Reservation: kein Doppelsitzer für den Verein erfasst.")
                .doesNotContain("Reservation erstellt.");
    }

    /**
     * The whole point of the port: legacy binds {@code PassengerFlightRegistration-
     * Model} but interpolates {@code $!TrialFlightRegistrationModel.*}
     * ({@code RegistrationEmailBuildService.cs:211-213,271-273}), and Velocity's
     * silent {@code $!} swallows the miss.
     */
    @Test
    void a_scenic_registration_renders_the_contact_fields_legacy_left_blank() {
        intake.acceptScenic(slug, "198.51.100.43", registrant(true));

        assertCandidateFieldsRendered(only(SUBJECT_SCENIC_CANDIDATE));
        assertCandidateFieldsRendered(only(SUBJECT_SCENIC_ORGANISER));
    }

    @Test
    void the_confirmation_goes_to_the_notification_email_when_the_invoice_address_differs() {
        intake.acceptScenic(slug, "198.51.100.44", registrant(false));

        MailMessage candidate = only(SUBJECT_SCENIC_CANDIDATE);
        assertThat(candidate.to()).containsExactly("beat.bezahler@example.ch");
        assertThat(candidate.htmlBody()).contains("Beat Bezahler").contains("Buchhaltungsweg 3");
    }

    @Test
    void a_registrant_without_an_email_address_is_still_registered() {
        Accepted accepted = intake.acceptScenic(slug, "198.51.100.45", reachableByPhoneOnly());

        assertThat(accepted.registered().registrantPersonId()).isNotNull();
        assertThat(subjects()).containsExactly(SUBJECT_SCENIC_ORGANISER);
        assertThat(outbox.sent()).allSatisfy(
                message -> assertThat(message.to()).containsExactlyElementsOf(ORGANISERS));
    }

    @Test
    void a_club_without_an_organiser_address_still_takes_the_registration() {
        clearOrganiserAddresses();

        Accepted accepted = intake.acceptScenic(slug, "198.51.100.46", registrant(true));

        assertThat(accepted.registered().registrantPersonId()).isNotNull();
        assertThat(subjects()).containsExactly(SUBJECT_SCENIC_CANDIDATE);
        assertThat(outbox.sent()).allSatisfy(
                message -> assertThat(message.to()).containsExactly("rosa.renggli@example.ch"));
    }

    @Test
    void a_club_override_wins_over_the_shipped_template() {
        Tenants.runAs(clubId, () -> overrides.save(EmailTemplate.customize(
                TEMPLATE_SCENIC_CANDIDATE, EmailTemplateCatalog.defaultLocale(),
                "Vereinsbetreff", "<p>Vereinseigener Bestätigungstext</p>")));

        intake.acceptScenic(slug, "198.51.100.47", registrant(true));

        assertThat(only(SUBJECT_SCENIC_CANDIDATE).htmlBody())
                .contains("Vereinseigener Bestätigungstext")
                .doesNotContain("Rechnungsadresse");
        assertThat(only(SUBJECT_SCENIC_ORGANISER).htmlBody())
                .as("only the overridden key is replaced")
                .contains("Rechnungsadresse");
    }

    /**
     * The coupon recipient the organiser has to post the voucher to. Both
     * choices are driven, because a template hardcoding either name renders
     * plausibly on one of them.
     */
    @Test
    void the_organiser_learns_which_of_the_two_people_gets_the_coupon() {
        intake.acceptScenic(slug, "198.51.100.48", registrant(false, true));
        assertThat(only(SUBJECT_SCENIC_ORGANISER).htmlBody())
                .contains("Gutschein an:")
                .containsSubsequence("Gutschein an:", "Beat Bezahler");

        outbox.clear();
        intake.acceptScenic(slug, "198.51.100.49", registrant(false, false));
        assertThat(only(SUBJECT_SCENIC_ORGANISER).htmlBody())
                .containsSubsequence("Gutschein an:", "Rosa Renggli");
    }

    /** Every skip reason the booker can produce needs its own copy, not just the two above. */
    @Test
    void every_reservation_outcome_has_its_own_organiser_sentence() {
        Set<String> sentences = new LinkedHashSet<>();

        for (Status status : Status.values()) {
            Map<String, Object> model = blankModel();
            model.put("reservationMessageKey", status.messageKey());

            String sentence = reservationSentence(mail.render(TEMPLATE_DISCOVERY_ORGANISER, model));
            assertThat(sentence).as("organiser copy for %s", status).isNotBlank();
            sentences.add(sentence);
        }

        assertThat(sentences).hasSize(Status.values().length);
    }

    private static String reservationSentence(String html) {
        Matcher section = Pattern.compile("Flugzeug-Reservation</h3>(.*?)<h3", Pattern.DOTALL)
                .matcher(html);
        assertThat(section.find()).as("rendered organiser mail has a reservation section").isTrue();
        return section.group(1).replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
    }

    /**
     * The five fields legacy renders blank in its scenic mails, asserted with
     * their labels so a template that dropped the line cannot pass either.
     */
    private static void assertCandidateFieldsRendered(MailMessage message) {
        assertThat(message.htmlBody())
                .contains("Rosa Renggli")
                .contains("rosa.renggli@example.ch")
                .contains("Mobil: 079 555 66 77")
                .contains("Privat: 041 660 11 22")
                .contains("Geschäft: 041 660 33 44")
                .contains(REMARKS);
    }

    private MailMessage only(String subject) {
        List<MailMessage> matching = outbox.sent().stream()
                .filter(message -> subject.equals(message.subject()))
                .toList();
        assertThat(matching).as("messages with subject '%s'", subject).hasSize(1);
        return matching.getFirst();
    }

    private List<String> subjects() {
        return outbox.sent().stream().map(MailMessage::subject).toList();
    }

    private static PublicRegistrantDetails registrant(boolean invoiceAddressIsSame) {
        return registrant(invoiceAddressIsSame, false);
    }

    private static PublicRegistrantDetails registrant(boolean invoiceAddressIsSame,
            boolean sendCouponToInvoiceAddress) {
        return new PublicRegistrantDetails(
                "Rosa", "Renggli", "Flugplatzstrasse 7", "6060", "Sarnen", null,
                "041 660 11 22", "041 660 33 44", "079 555 66 77", "rosa.renggli@example.ch",
                REMARKS, invoiceAddressIsSame, sendCouponToInvoiceAddress,
                invoiceAddressIsSame ? null : new InvoiceRecipient(
                        "Beat", "Bezahler", "Buchhaltungsweg 3", "6003", "Luzern", null,
                        "beat.bezahler@example.ch"));
    }

    private static PublicRegistrantDetails reachableByPhoneOnly() {
        return new PublicRegistrantDetails(
                "Rosa", "Renggli", "Flugplatzstrasse 7", "6060", "Sarnen", null,
                null, null, "079 555 66 77", null, null, true, false, null);
    }

    /** Every key the organiser template dereferences, so only the switch varies. */
    private static Map<String, Object> blankModel() {
        Map<String, Object> model = new HashMap<>();
        for (String key : List.of("clubName", "locationName", "flightDate", "contactDate",
                "candidateName", "addressLine1", "zip", "city", "privateEmail", "mobilePhone",
                "privatePhone", "businessPhone", "remarks", "invoiceName", "invoiceAddressLine1",
                "invoiceZip", "invoiceCity", "couponRecipientName")) {
            model.put(key, "");
        }
        return model;
    }

/**
     * The intake rejects a day the club never published, so every discovery
     * case here needs the picker's day to genuinely exist.
     */
    private void publishDiscoveryDay(LocalDate eventDate) {
        TenantTestContext.runAs(clubId, () ->
                discoveryDays.save(DiscoveryFlightDay.schedule(eventDate, eventDate)));
    }

    private void clearOrganiserAddresses() {
        Club club = clubs.findActiveById(clubId).orElseThrow();
        club.setRegistrationOperatorEmails(null, null);
        clubs.save(club);
    }

    private void giveClubAHomebase() {
        UUID homebaseId = TenantTestContext.runAs(clubId, () -> {
            Location home = locations.save(Location.create(
                    HOMEBASE, null, firstCountryId(), firstLocationTypeId(),
                    null, null, null, null, null, null, null, null, null, null, null,
                    false, false, false));
            return Objects.requireNonNull(home.getId()).value();
        });
        // No aggregate write path for the homebase FK yet (Club.java:413).
        jdbc.update("UPDATE t_club SET homebase_id = ?::uuid WHERE id = ?::uuid",
                homebaseId.toString(), clubId.toString());
    }

    private void giveClubADoubleSeaterGlider() {
        aircraft.save(Aircraft.register(
                clubId, clubId, aircraftTypeId(), "HB-PRM1",
                null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null,
                false, false, false, false, null, null));
    }

    private UUID aircraftTypeId() {
        return aircraftTypes.findAllByOrderByLegacyIntIdAsc().stream()
                .filter(type -> "GLIDER".equals(type.getCode()))
                .map(AircraftType::getId)
                .filter(Objects::nonNull)
                .map(id -> id.value())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No t_aircraft_type row GLIDER"));
    }

    private UUID firstCountryId() {
        return Objects.requireNonNull(countries.findAllOrdered().getFirst().getId()).value();
    }

    private UUID firstLocationTypeId() {
        return Objects.requireNonNull(
                locationTypes.findAllByOrderByDescriptionAsc().getFirst().getId()).value();
    }
}

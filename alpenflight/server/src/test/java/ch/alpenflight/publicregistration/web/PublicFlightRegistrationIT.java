package ch.alpenflight.publicregistration.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.referencedata.domain.AircraftType;
import ch.alpenflight.referencedata.domain.AircraftTypeRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.referencedata.domain.LocationTypeRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.PublicSubmissions;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PublicFlightRegistrationIT extends PostgresIntegrationTest {

    private static final LocalDate BOOKABLE_DAY = LocalDate.of(2099, 6, 15);
    private static final LocalDate PAST_DAY = LocalDate.of(2020, 6, 15);
    private static final LocalDate WITHDRAWN_DAY = LocalDate.of(2099, 8, 25);
    private static final LocalDate OTHER_CLUBS_DAY = LocalDate.of(2099, 7, 1);
    private static final LocalDate NEVER_PUBLISHED_DAY = LocalDate.of(2099, 9, 30);
    private static final String UNKNOWN_SLUG = "no-such-club-it-dfr";

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-02T09:00:00Z"), ZoneOffset.UTC);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired LocationTypeRepository locationTypes;
    @Autowired LocationRepository locations;
    @Autowired AircraftRepository aircraft;
    @Autowired AircraftTypeRepository aircraftTypes;
    @Autowired DiscoveryFlightDayRepository discoveryDays;

    private UUID openClubId;
    private UUID otherClubId;
    private String openSlug;
    private String closedSlug;
    private String openClubName;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DFR_", "IT_DFR_");
        fixture.seed();
        openClubId = fixture.clubA();
        otherClubId = fixture.clubB();

        Club open = clubs.findActiveById(openClubId).orElseThrow();
        open.enablePublicRegistration();
        clubs.save(open);
        openSlug = requireSlug(open);
        openClubName = open.getClubname();
        closedSlug = requireSlug(clubs.findActiveById(otherClubId).orElseThrow());

        giveClubAHomebase();
        registerDoubleSeaterGlider();
        publish(openClubId, BOOKABLE_DAY);
        publish(openClubId, PAST_DAY);
        withdraw(openClubId, publish(openClubId, WITHDRAWN_DAY));
        publish(otherClubId, OTHER_CLUBS_DAY);
    }

    @Test
    void the_picker_is_offered_only_the_clubs_own_still_bookable_days() {
        ResponseEntity<List<String>> response = getDays(openSlug);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsExactly(BOOKABLE_DAY.toString())
                .doesNotContain(PAST_DAY.toString(), WITHDRAWN_DAY.toString(),
                        OTHER_CLUBS_DAY.toString());
    }

    @Test
    void a_club_that_published_no_days_gets_an_empty_list_rather_than_a_404() {
        Club other = clubs.findActiveById(otherClubId).orElseThrow();
        other.enablePublicRegistration();
        clubs.save(other);
        withdrawAll(otherClubId);

        ResponseEntity<List<String>> response = getDays(requireSlug(other));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void the_days_read_keeps_the_slug_contract() {
        assertThat(getDays(UNKNOWN_SLUG).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getDays(closedSlug).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void a_submission_on_a_published_day_is_201() {
        ResponseEntity<Map<String, Object>> response = submit(openSlug, BOOKABLE_DAY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = Objects.requireNonNull(response.getBody());
        assertThat(body.get("clubName")).isEqualTo(openClubName);
        assertThat(body.get("selectedDay")).isEqualTo(BOOKABLE_DAY.toString());
        String registrantPersonId = (String) body.get("registrantPersonId");
        assertThat(registrantPersonId).startsWith("pn-");
        assertThat(Objects.requireNonNull(response.getHeaders().getLocation()).getPath())
                .isEqualTo("/api/v1/persons/" + registrantPersonId);

        assertThat(registrants()).isOne();
        assertThat(reservations())
                .as("the club is bookable, so a good date genuinely blocks the glider")
                .isOne();
    }

    @Test
    void a_scenic_submission_is_201_and_books_nothing_where_discovery_books() {
        ResponseEntity<Map<String, Object>> response = submitScenic(openSlug);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = Objects.requireNonNull(response.getBody());
        assertThat(body.get("clubName")).isEqualTo(openClubName);
        assertThat(body).doesNotContainKey("selectedDay");
        String registrantPersonId = (String) body.get("registrantPersonId");
        assertThat(registrantPersonId).startsWith("pn-");
        assertThat(Objects.requireNonNull(response.getHeaders().getLocation()).getPath())
                .isEqualTo("/api/v1/persons/" + registrantPersonId);

        assertThat(registrants()).isOne();
        assertThat(memberships()).isOne();
        assertThat(traineeMarkers())
                .as("a scenic passenger is no glider trainee, at person or membership level")
                .containsExactly(false, false);
        assertThat(reservations()).isZero();

        assertThat(submit(openSlug, BOOKABLE_DAY).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reservations())
                .as("the same club books for discovery, so the scenic zero is the flow's doing")
                .isOne();
    }

    @Test
    void a_scenic_submission_carrying_a_selectedDay_is_rejected() {
        Map<String, Object> body = PublicSubmissions.scenicBody();
        body.put("selectedDay", BOOKABLE_DAY.toString());

        assertThat(rest.postForEntity(scenicPath(openSlug), body, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(registrants()).isZero();
        assertThat(reservations()).isZero();
    }

    @Test
    void a_day_that_has_passed_is_rejected_and_registers_nobody() {
        assertRejectedAndNothingWritten(PAST_DAY);
    }

    @Test
    void a_withdrawn_day_is_rejected_and_registers_nobody() {
        assertRejectedAndNothingWritten(WITHDRAWN_DAY);
    }

    @Test
    void a_day_published_by_another_club_is_rejected_and_registers_nobody() {
        assertRejectedAndNothingWritten(OTHER_CLUBS_DAY);
    }

    @Test
    void a_day_nobody_published_is_rejected_and_registers_nobody() {
        assertRejectedAndNothingWritten(NEVER_PUBLISHED_DAY);
    }

    @Test
    void the_registrant_field_contract_is_enforced_through_http() {
        Map<String, Object> registrant = PublicSubmissions.registrant();
        registrant.remove("city");
        Map<String, Object> body = Map.of("registrant", registrant,
                "selectedDay", BOOKABLE_DAY.toString());

        assertThat(rest.postForEntity(registrationPath(openSlug), body, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(registrants()).isZero();
    }

    @Test
    void an_omitted_coupon_choice_means_the_default_rather_than_a_rejection() {
        Map<String, Object> registrant =
                PublicSubmissions.registrantWithDifferingInvoiceAddress(true);
        registrant.remove("sendCouponToInvoiceAddress");
        Map<String, Object> body = Map.of("registrant", registrant,
                "selectedDay", BOOKABLE_DAY.toString());

        assertThat(rest.postForEntity(registrationPath(openSlug), body, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(registrants()).isOne();
        assertThat(invoiceRecipients())
                .as("the differing invoice address still writes its own Person")
                .isOne();
    }

    @Test
    void a_submission_without_a_day_is_rejected() {
        assertThat(rest.postForEntity(registrationPath(openSlug),
                        Map.of("registrant", PublicSubmissions.registrant()), Void.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(registrants()).isZero();
    }

    private void assertRejectedAndNothingWritten(LocalDate selectedDay) {
        ResponseEntity<Map<String, Object>> response = submit(openSlug, selectedDay);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(registrants()).as("registrant Person").isZero();
        assertThat(memberships()).as("PersonClub in the target club").isZero();
        assertThat(reservations()).as("aircraft reservation").isZero();
    }

    private ResponseEntity<Map<String, Object>> submit(String slug, LocalDate selectedDay) {
        return rest.exchange(registrationPath(slug), HttpMethod.POST,
                new HttpEntity<>(PublicSubmissions.discoveryBody(selectedDay)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<Map<String, Object>> submitScenic(String slug) {
        return rest.exchange(scenicPath(slug), HttpMethod.POST,
                new HttpEntity<>(PublicSubmissions.scenicBody()),
                new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private ResponseEntity<List<String>> getDays(String slug) {
        return rest.exchange("/api/v1/public/clubs/" + slug + "/discovery-flight-days",
                HttpMethod.GET, null, new ParameterizedTypeReference<List<String>>() {});
    }

    private static String registrationPath(String slug) {
        return "/api/v1/public/clubs/" + slug + "/discovery-flight-registrations";
    }

    private static String scenicPath(String slug) {
        return "/api/v1/public/clubs/" + slug + "/scenic-flight-registrations";
    }

    private long registrants() {
        return clubMembersNamed(PublicSubmissions.LASTNAME);
    }

    private long invoiceRecipients() {
        return clubMembersNamed(PublicSubmissions.INVOICE_LASTNAME);
    }

    private long clubMembersNamed(String lastname) {
        return count("SELECT count(*) FROM t_person p "
                + "JOIN t_person_club pc ON pc.person_id = p.id "
                + "WHERE p.lastname = ? AND pc.club_id = ?::uuid",
                lastname, openClubId.toString());
    }

    private List<Boolean> traineeMarkers() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT p.has_glider_trainee_licence AS person_marker, "
                        + "pc.is_glider_trainee AS membership_marker "
                        + "FROM t_person p JOIN t_person_club pc ON pc.person_id = p.id "
                        + "WHERE p.lastname = ? AND pc.club_id = ?::uuid",
                PublicSubmissions.LASTNAME, openClubId.toString());
        return List.of((Boolean) row.get("person_marker"), (Boolean) row.get("membership_marker"));
    }

    private long memberships() {
        return count("SELECT count(*) FROM t_person_club WHERE club_id = ?::uuid",
                openClubId.toString());
    }

    private long reservations() {
        return count("SELECT count(*) FROM t_aircraft_reservation "
                + "WHERE operating_club_id = ?::uuid AND deleted_on IS NULL",
                openClubId.toString());
    }

    private long count(String sql, Object... args) {
        Long rows = jdbc.queryForObject(sql, Long.class, args);
        return rows == null ? 0L : rows;
    }

    private UUID publish(UUID clubId, LocalDate eventDate) {
        return TenantTestContext.runAs(clubId, () -> Objects.requireNonNull(
                discoveryDays.save(DiscoveryFlightDay.schedule(eventDate, eventDate)).getId()));
    }

    private void withdraw(UUID clubId, UUID dayId) {
        TenantTestContext.runAs(clubId, () -> {
            DiscoveryFlightDay day = discoveryDays.findActiveById(dayId).orElseThrow();
            day.softDelete(null, clock);
            discoveryDays.save(day);
        });
    }

    private void withdrawAll(UUID clubId) {
        TenantTestContext.runAs(clubId, () -> discoveryDays.findAllActive().forEach(day -> {
            day.softDelete(null, clock);
            discoveryDays.save(day);
        }));
    }

    private void giveClubAHomebase() {
        TenantTestContext.runAs(openClubId, () -> {
            Location home = locations.save(Location.create(
                    "IT_DFR_Homebase", null, firstCountryId(), firstLocationTypeId(),
                    null, null, null, null, null, null, null, null, null, null, null,
                    false, false, false));
            Club club = clubs.findActiveById(openClubId).orElseThrow();
            club.relocateHomebase(Objects.requireNonNull(home.getId()).value(),
                    id -> locations.findActiveById(id).isPresent());
            clubs.save(club);
        });
    }

    private void registerDoubleSeaterGlider() {
        aircraft.save(Aircraft.register(
                openClubId, openClubId, gliderTypeId(), "HB-DFR1",
                null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null,
                false, false, false, false, null, null));
    }

    private UUID gliderTypeId() {
        return aircraftTypes.findAllByOrderByLegacyIntIdAsc().stream()
                .filter(type -> "GLIDER".equals(type.getCode()))
                .map(AircraftType::getId)
                .filter(Objects::nonNull)
                .map(id -> id.value())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No t_aircraft_type row GLIDER"));
    }

    private UUID firstCountryId() {
        return Objects.requireNonNull(countries.findAllOrderedByNameUnderIcuCollation().getFirst().getId()).value();
    }

    private UUID firstLocationTypeId() {
        return Objects.requireNonNull(
                locationTypes.findAllByOrderByDescriptionAsc().getFirst().getId()).value();
    }

    private static String requireSlug(Club club) {
        String slug = club.getSlug();
        if (slug == null) {
            throw new IllegalStateException("Fixture club has no slug");
        }
        return slug;
    }
}

package ch.alpenflight.tenancy.sandbox.web;

import static ch.alpenflight.tenancy.sandbox.application.DemoSeatPoolTestFixture.returnEverySeatToThePool;
import static ch.alpenflight.tenancy.sandbox.application.DemoSeatPoolTestFixture.seatNumbered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.alpenflight.locations.application.LocationDtos.LocationCreateRequest;
import ch.alpenflight.locations.application.LocationsService;
import ch.alpenflight.platform.id.CountryId;
import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.id.LocationTypeId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.application.ReferenceDataService;
import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.tenancy.sandbox.application.DemoSeatLeaseProperties;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import ch.alpenflight.tenancy.sandbox.domain.NoDemoSeatAvailableException.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwtTestFixture.class)
@TestPropertySource(properties = {
        "demo.pool-size=1",
        "keycloak.admin.realm=alpenflight"
})
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class DemoSessionControllerIT {

    private static final String DEMO_SESSION_PATH = "/api/v1/public/demo-session";

    private static final String PUBLIC_POST_PATH_THE_ENUMERATION_DOES_NOT_NAME =
            "/api/v1/public/clubs/any-slug/join-requests";

    private static final String VISITOR_ADDRESS = "198.51.100.10";
    private static final String A_SECOND_VISITOR_ADDRESS = "198.51.100.11";
    private static final String FORGED_FORWARDED_FOR_ADDRESS = "203.0.113.77";

    private static final String CLIENT_ID_THE_SERVER_HOLDS = "alpenflight-demo-seat";
    private static final String CLIENT_SECRET_THE_SERVER_HOLDS = "alpenflight-demo-seat-dev-secret";
    private static final String SEAT_PASSWORD_THE_SERVER_HOLDS = "alpenflight-demo-seat-dev-2026!";

    private static final long ACCESS_TOKEN_LIFESPAN_THE_REALM_PUBLISHES_IN_SECONDS = 900;

    private static final int SEAT_INSIDE_THE_POOL = 1;
    private static final int SEAT_OUTSIDE_THE_POOL = 2;

    private static final String COUNTRY_ISO2_SWITZERLAND = "CH";
    private static final String LOCATION_TYPE_GLIDER_AIRFIELD = "GLIDER_AIRFIELD";

    private static final String REFUSAL_BODY_KEYCLOAK_RETURNS_FOR_A_BAD_CREDENTIAL =
            "{\"error\":\"invalid_grant\"}";

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;

    private static final HttpServer KEYCLOAK_TOKEN_ENDPOINT = startTheKeycloakTokenEndpointStub();

    private static volatile Function<Map<String, String>, String> theRealmAnswersTheDirectGrant =
            form -> "";

    @DynamicPropertySource
    static void datasourceAndTheStubbedRealmTokenEndpoint(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::jdbcUrl);
        r.add("spring.datasource.username", POSTGRES::username);
        r.add("spring.datasource.password", POSTGRES::password);
        r.add("spring.flyway.url", POSTGRES::jdbcUrl);
        r.add("spring.flyway.user", POSTGRES::username);
        r.add("spring.flyway.password", POSTGRES::password);
        r.add("keycloak.admin.base-url",
                () -> "http://127.0.0.1:" + KEYCLOAK_TOKEN_ENDPOINT.getAddress().getPort());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtTestFixture jwts;

    @Autowired
    private DemoSeatRepository seats;

    @Autowired
    private DemoSeatLeaseProperties leaseProperties;

    @Autowired
    private ReferenceDataService referenceData;

    @Autowired
    private LocationsService locations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private final List<UUID> locationsThisTestWroteInsideASeatClub = new ArrayList<>();

    @BeforeEach
    void everySeatStartsFreeAndTheRealmMintsForTheServerHeldCredential() {
        returnEverySeatToThePool(seats, transactionManager, clock);
        theRealmAnswersTheDirectGrant = this::theTokenTheRealmMintsForAMatchingCredential;
    }

    @AfterEach
    void everySeatGoesBackSoTheNextTestClassReadsThePoolAsFlywayCreatedIt() {
        returnEverySeatToThePool(seats, transactionManager, clock);
        deleteTheLocationsThisTestWroteInsideASeatClub();
    }

    private void deleteTheLocationsThisTestWroteInsideASeatClub() {
        for (UUID locationId : locationsThisTestWroteInsideASeatClub) {
            jdbc.update("DELETE FROM t_location WHERE id = ?::uuid", locationId.toString());
        }
        locationsThisTestWroteInsideASeatClub.clear();
    }

    @AfterAll
    static void theStubbedRealmReleasesItsPortAndItsExecutorThread() {
        KEYCLOAK_TOKEN_ENDPOINT.stop(0);
    }

    @Test
    void the_front_door_takes_an_anonymous_post_while_an_unenumerated_public_post_stays_401()
            throws Exception {
        String body = mvc.perform(post(DEMO_SESSION_PATH).with(fromPeerAddress(VISITOR_ADDRESS)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode granted = json.readTree(body);
        assertThat(granted.get("accessToken").asText()).isNotBlank();
        assertThat(granted.get("expiresInSeconds").asLong())
                .isEqualTo(ACCESS_TOKEN_LIFESPAN_THE_REALM_PUBLISHES_IN_SECONDS);
        assertThat(granted.get("leaseExpiresAt").asText()).isNotBlank();
        assertThat(body)
                .as("the front door hands the visitor an access token, never a refresh token")
                .doesNotContain("refresh_token");

        mvc.perform(post(PUBLIC_POST_PATH_THE_ENUMERATION_DOES_NOT_NAME)
                        .with(fromPeerAddress(VISITOR_ADDRESS)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void the_leased_seats_token_reads_that_seats_club_and_never_the_neighbour_seats_club()
            throws Exception {
        UUID leasableSeatClub = seatNumbered(seats, SEAT_INSIDE_THE_POOL).getClubId().value();
        UUID neighbourSeatClub = seatNumbered(seats, SEAT_OUTSIDE_THE_POOL).getClubId().value();
        String nameOnlyTheLeasedSeatMayRead = aLocationNamed("Seat-under-lease field", leasableSeatClub);
        String nameNoDemoVisitorMayReadFromAnotherSeat =
                aLocationNamed("Neighbour seat field", neighbourSeatClub);

        String accessToken = json
                .readTree(mvc.perform(post(DEMO_SESSION_PATH).with(fromPeerAddress(VISITOR_ADDRESS)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("accessToken").asText();

        String locationsTheSeatReads = mvc
                .perform(get("/api/v1/locations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(locationsTheSeatReads)
                .as("the leased token carries the seat's club, so it reads that club's rows")
                .contains(nameOnlyTheLeasedSeatMayRead)
                .as("a demo visitor never reads another seat's sandbox")
                .doesNotContain(nameNoDemoVisitorMayReadFromAnotherSeat);
    }

    @Test
    void an_exhausted_pool_answers_503_with_the_readable_reason() throws Exception {
        mvc.perform(post(DEMO_SESSION_PATH).with(fromPeerAddress(VISITOR_ADDRESS)))
                .andExpect(status().isOk());

        String problem = mvc
                .perform(post(DEMO_SESSION_PATH).with(fromPeerAddress(A_SECOND_VISITOR_ADDRESS)))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(problem).get("type").asText())
                .isEqualTo(DemoSessionExceptionHandler.TYPE_POOL_EXHAUSTED.toString());
        assertThat(json.readTree(problem).get("title").asText())
                .isEqualTo(DemoSessionExceptionHandler.TITLE_POOL_EXHAUSTED);
        assertThat(json.readTree(problem).get("detail").asText())
                .isEqualTo(Reason.EVERY_DEMO_SEAT_IS_IN_USE.readableReason());
        assertThat(fieldNamesOf(json.readTree(problem)))
                .as("the mocked inner-loop fixture at alpenflight/web/e2e/tests/demo/demo-mode.spec.ts"
                        + " mirrors this field set")
                .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance");
        assertThat(json.readTree(problem).get("instance").asText()).isEqualTo(DEMO_SESSION_PATH);
    }

    @Test
    void a_second_session_from_one_address_answers_503_at_the_production_default_of_one_seat()
            throws Exception {
        assertThat(leaseProperties.maxLiveSeatsPerAddress())
                .as("this case runs at the production default of demo.max-live-seats-per-address")
                .isEqualTo(1);

        mvc.perform(post(DEMO_SESSION_PATH).with(fromPeerAddress(VISITOR_ADDRESS)))
                .andExpect(status().isOk());

        String problem = mvc.perform(post(DEMO_SESSION_PATH).with(fromPeerAddress(VISITOR_ADDRESS)))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(problem).get("type").asText())
                .isEqualTo(DemoSessionExceptionHandler.TYPE_ADDRESS_HOLDS_A_SEAT.toString());
        assertThat(json.readTree(problem).get("detail").asText())
                .isEqualTo(Reason.THIS_ADDRESS_ALREADY_HOLDS_A_LIVE_DEMO_SEAT.readableReason());
    }

    @Test
    void a_forged_forwarded_for_header_does_not_lift_the_cap_of_a_public_peer() throws Exception {
        mvc.perform(post(DEMO_SESSION_PATH).with(fromPeerAddress(VISITOR_ADDRESS)))
                .andExpect(status().isOk());

        String problem = mvc.perform(post(DEMO_SESSION_PATH)
                        .with(fromPeerAddress(VISITOR_ADDRESS))
                        .header("X-Forwarded-For", FORGED_FORWARDED_FOR_ADDRESS))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(problem).get("detail").asText())
                .isEqualTo(Reason.THIS_ADDRESS_ALREADY_HOLDS_A_LIVE_DEMO_SEAT.readableReason());
    }

    private static List<String> fieldNamesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String aLocationNamed(String locationName, UUID clubId) {
        String uniqueName = locationName + " " + UUID.randomUUID();
        CountryId switzerland = referenceData.listCountries().stream()
                .filter(country -> COUNTRY_ISO2_SWITZERLAND.equals(country.iso2Code()))
                .findFirst().orElseThrow().id();
        LocationTypeId gliderAirfield = referenceData.listLocationTypes().stream()
                .filter(type -> LOCATION_TYPE_GLIDER_AIRFIELD.equals(type.code()))
                .findFirst().orElseThrow().id();
        LocationId created = TenantTestContext.runAs(clubId, () ->
                locations.createLocation(new LocationCreateRequest(
                        uniqueName, null, switzerland, gliderAirfield, null,
                        null, null, null, null, null, null, null, null, null, null,
                        false, false, false, null)).id());
        locationsThisTestWroteInsideASeatClub.add(created.value());
        return uniqueName;
    }

    private String theTokenTheRealmMintsForAMatchingCredential(Map<String, String> form) {
        boolean theCredentialMatchesTheOneTheRealmPublishes =
                "password".equals(form.get("grant_type"))
                        && CLIENT_ID_THE_SERVER_HOLDS.equals(form.get("client_id"))
                        && CLIENT_SECRET_THE_SERVER_HOLDS.equals(form.get("client_secret"))
                        && SEAT_PASSWORD_THE_SERVER_HOLDS.equals(form.get("password"));
        if (!theCredentialMatchesTheOneTheRealmPublishes) {
            return "";
        }
        String seatPrincipal = form.getOrDefault("username", "");
        DemoSeat seat = seats.findAllInSeatNumberOrder().stream()
                .filter(candidate -> candidate.getKeycloakUsername().equals(seatPrincipal))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no demo seat carries the principal " + seatPrincipal));
        String token = jwts.mintJitReady(
                UUID.nameUUIDFromBytes(seatPrincipal.getBytes(StandardCharsets.UTF_8)),
                seat.getClubId().value(),
                claims -> claims
                        .claim("preferred_username", seatPrincipal)
                        .claim("given_name", "Demo")
                        .claim("family_name", "Seat")
                        .claim("email", seatPrincipal + "@example.com")
                        .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        return "{\"access_token\":\"" + token + "\",\"expires_in\":"
                + ACCESS_TOKEN_LIFESPAN_THE_REALM_PUBLISHES_IN_SECONDS + "}";
    }

    private static RequestPostProcessor fromPeerAddress(String peerAddress) {
        return request -> {
            request.setRemoteAddr(peerAddress);
            return request;
        };
    }

    private static HttpServer startTheKeycloakTokenEndpointStub() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/realms/alpenflight/protocol/openid-connect/token",
                    DemoSessionControllerIT::answerTheDirectGrant);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void answerTheDirectGrant(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String minted = theRealmAnswersTheDirectGrant.apply(form);
        boolean refused = minted.isEmpty();
        byte[] out = (refused ? REFUSAL_BODY_KEYCLOAK_RETURNS_FOR_A_BAD_CREDENTIAL : minted)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(refused ? 401 : 200, out.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(out);
        }
    }

    private static Map<String, String> parseForm(String encoded) {
        Map<String, String> form = new HashMap<>();
        for (String pair : encoded.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                form.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }
}

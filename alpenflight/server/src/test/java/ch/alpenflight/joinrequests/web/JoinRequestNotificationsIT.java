package ch.alpenflight.joinrequests.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.emailtemplates.domain.EmailTemplate;
import ch.alpenflight.emailtemplates.domain.EmailTemplateRepository;
import ch.alpenflight.me.application.MePrincipalEventBus;
import ch.alpenflight.platform.mail.CapturedMailSender;
import ch.alpenflight.platform.mail.MailMessage;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import ch.alpenflight.users.domain.UserDirectoryPort;
import ch.alpenflight.users.domain.UserDirectoryPort.RealmRoleRef;
import ch.alpenflight.users.domain.UserDirectoryPort.UserDirectoryRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Notifications slice (S-178, T-08): each committed join-request transition
 * sends the matching transactional email AND publishes a
 * {@code join-request.status-changed} SSE event to the right principal.
 *
 * <ul>
 *   <li>submit → {@code admin-new-request} mail to the club admins + SSE to each
 *       admin sub (their pending list gained a row);</li>
 *   <li>approve → {@code pilot-approved} mail + SSE to the pilot sub;</li>
 *   <li>deny → {@code pilot-denied} mail + SSE to the pilot sub;</li>
 *   <li>withdraw → {@code pilot-withdrawn} mail + SSE to the pilot sub.</li>
 * </ul>
 *
 * <p>Mail is asserted via the {@link CapturedMailSender} outbox; the SSE publish
 * via a mocked {@link MePrincipalEventBus} (the in-memory transport no-ops with
 * no open stream, so the publish CALL is the deterministic assertion — the live
 * stream end-to-end is the journey's real-idp gate). The notification listeners
 * run AFTER_COMMIT on a separate thread, so SSE verifies use a short timeout.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({JwtTestFixture.class, CapturedMailSender.Config.class})
class JoinRequestNotificationsIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EVENT_KIND = "join-request.status-changed";
    private static final String ADMIN_TEMPLATE_KEY = "join-request-admin-new-request";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired CapturedMailSender outbox;
    @Autowired EmailTemplateRepository overrides;
    @MockitoBean UserDirectoryPort directory;
    // Spy (not mock) the bus so the concrete InMemoryMePrincipalEventBus the
    // events controller injects by type keeps its runtime type; the publish
    // call is verifiable without an open stream (no-op when no stream is live).
    @MockitoSpyBean MePrincipalEventBus bus;

    private UUID clubA;
    private String codeA;
    private UUID adminSubA;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_JRN_", "IT_JRN_");
        fixture.seed();
        clubA = fixture.clubA();
        codeA = clubs.findActiveById(clubA).map(Club::getJoinCode).orElseThrow();
        adminSubA = UuidCreator.getTimeOrderedEpoch();
        seedUser(adminSubA, clubA, "admin-a", "admin-a@example.com");
        outbox.clear();
        jdbc.update("DELETE FROM t_email_template WHERE club_id = ?::uuid", clubA.toString());
        // The admin set is the directory's CLUB_ADMINISTRATOR holders intersected
        // with the club's local rows; stub the directory to surface admin A.
        when(directory.findUsersByRoleName("CLUB_ADMINISTRATOR"))
                .thenReturn(List.of(new UserDirectoryRow(adminSubA, "admin-a", "admin-a@example.com",
                        true, null, null)));
        when(directory.findRealmRolesByName(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.Set<String> names = (java.util.Set<String>) inv.getArgument(0);
            return names.stream().map(n -> new RealmRoleRef(UUID.randomUUID().toString(), n, null)).toList();
        });
    }

    @Test
    void submit_mailsAdmins_andSseToAdminSub() {
        UUID pilotSub = UuidCreator.getTimeOrderedEpoch();
        submit(pilotToken(pilotSub), codeA, "let me in");

        verify(bus, timeout(5000)).publish(eq(adminSubA.toString()), eq(EVENT_KIND), any());
        MailMessage mail = onlyMailTo("admin-a@example.com");
        assertThat(mail.subject()).isEqualTo("Neue Beitrittsanfrage");
        assertThat(mail.htmlBody()).contains("Beitrittsanfrage");
    }

    @Test
    void approve_mailsPilot_andSseToPilotSub() {
        UUID pilotSub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(pilotSub);
        outbox.clear();

        approve(adminToken(clubA, adminSubA), reqId);

        verify(bus, timeout(5000)).publish(eq(pilotSub.toString()), eq(EVENT_KIND), any());
        assertThat(onlyMailTo(pilotEmail(pilotSub)).subject()).isEqualTo("Beitrittsanfrage genehmigt");
    }

    @Test
    void deny_mailsPilotWithReason_andSseToPilotSub() {
        UUID pilotSub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(pilotSub);
        outbox.clear();

        deny(adminToken(clubA, adminSubA), reqId, "not this year");

        verify(bus, timeout(5000)).publish(eq(pilotSub.toString()), eq(EVENT_KIND), any());
        MailMessage mail = onlyMailTo(pilotEmail(pilotSub));
        assertThat(mail.subject()).isEqualTo("Beitrittsanfrage abgelehnt");
        assertThat(mail.htmlBody()).contains("not this year");
    }

    @Test
    void withdraw_mailsPilot_andSseToPilotSub() {
        UUID pilotSub = UuidCreator.getTimeOrderedEpoch();
        String reqId = filePending(pilotSub);
        outbox.clear();

        ResponseEntity<String> res = post(pilotToken(pilotSub),
                "/api/v1/join-requests/" + reqId + "/withdraw", Map.of());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(bus, timeout(5000)).publish(eq(pilotSub.toString()), eq(EVENT_KIND), any());
        assertThat(onlyMailTo(pilotEmail(pilotSub)).subject()).isEqualTo("Beitrittsanfrage zurückgezogen");
    }

    @Test
    void clubOverride_winsOverFileDefault_forJoinRequestTemplate() {
        Tenants.runAs(clubA, () -> overrides.save(EmailTemplate.customize(
                ADMIN_TEMPLATE_KEY, "de", "Custom subject", "<p>Overridden admin body</p>")));

        UUID pilotSub = UuidCreator.getTimeOrderedEpoch();
        submit(pilotToken(pilotSub), codeA, null);

        verify(bus, timeout(5000)).publish(eq(adminSubA.toString()), eq(EVENT_KIND), any());
        assertThat(onlyMailTo("admin-a@example.com").htmlBody())
                .contains("Overridden admin body")
                .doesNotContain("Pilotin / Pilot");
    }

    // ---- helpers ----

    private String filePending(UUID pilotSub) {
        ResponseEntity<String> res = submit(pilotToken(pilotSub), codeA, "let me in");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return readJson(res).get("id").asText();
    }

    private MailMessage onlyMailTo(String recipient) {
        List<MailMessage> matches = outbox.sent().stream()
                .filter(m -> m.to().contains(recipient))
                .toList();
        assertThat(matches).as("exactly one mail to %s", recipient).hasSize(1);
        return matches.get(0);
    }

    private static String pilotEmail(UUID sub) {
        return "pilot-" + sub + "@example.com";
    }

    private String pilotToken(UUID sub) {
        return jwts.mint(c -> c
                .subject(sub.toString())
                .claim("email", pilotEmail(sub))
                .claim("given_name", "Test")
                .claim("family_name", "Pilot")
                .claim("preferred_username", "pilot-" + sub)
                .claim("realm_access", Map.of("roles", List.of("PILOT"))));
    }

    private String adminToken(UUID clubId, UUID sub) {
        return jwts.mint(c -> c
                .subject(sub.toString())
                .claim("clubId", clubId.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private void seedUser(UUID sub, UUID clubId, String username, String email) {
        UUID languageId = jdbc.queryForObject("SELECT id FROM t_language LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO t_user (id, club_id, username, friendly_name, "
                        + "notification_email, language_id, keycloak_sub, created_on, modified_on) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid, now(), now())",
                UuidCreator.getTimeOrderedEpoch().toString(), clubId.toString(),
                username + "-" + sub, "Seed " + username, email,
                languageId.toString(), sub.toString());
    }

    private ResponseEntity<String> submit(String token, String joinCode, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("joinCode", joinCode);
        if (note != null) {
            body.put("note", note);
        }
        return post(token, "/api/v1/join-requests", body);
    }

    private ResponseEntity<String> approve(String token, String reqId) {
        return post(token, "/api/v1/join-requests/" + reqId + "/approve",
                Map.of("roles", List.of("PILOT")));
    }

    private ResponseEntity<String> deny(String token, String reqId, String reason) {
        return post(token, "/api/v1/join-requests/" + reqId + "/deny", Map.of("reason", reason));
    }

    private ResponseEntity<String> post(String token, String path, Map<String, Object> body) {
        RequestEntity<Map<String, Object>> req = RequestEntity.post(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        return rest.exchange(req, String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse: " + res.getBody(), e);
        }
    }
}

package ch.alpenflight.joinrequests.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Full-stack abuse-guard test for the join-request submit path (S-178, T-07).
 * A {@link MutableClock} drives the brute-force window + the 24h deny cooldown
 * so the test advances time deterministically instead of sleeping. The clock is
 * the one the guard AND the deny path share, so a denial's {@code decided_on}
 * and the cooldown check stay coherent.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class JoinRequestSubmitGuardIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Settable wall clock so the test can fast-forward past the window / cooldown. */
    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-06-23T12:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            this.now = this.now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired MutableClock clock;

    private UUID clubA;
    private String codeA;
    private UUID adminSubA;

    @BeforeEach
    void seed() {
        clock.now = Instant.parse("2026-06-23T12:00:00Z");
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_JRG_", "IT_JRG_");
        fixture.seed();
        clubA = fixture.clubA();
        codeA = clubs.findActiveById(clubA).map(Club::getJoinCode).orElseThrow();
        adminSubA = UuidCreator.getTimeOrderedEpoch();
        seedUser(adminSubA, clubA, "admin-g");
    }

    // ---- brute-force rate limit: 5 / 15 min per sub ----

    @Test
    void sixthAttemptInWindow_is_429_withRetryAfter() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String token = pilotToken(sub);
        // 5 unknown-code attempts still count toward the per-sub window.
        for (int i = 0; i < 5; i++) {
            assertThat(submit(token, "NOPE" + i + "AB", null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<String> sixth = submit(token, "NOPE5XYZ", null);
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(Long.parseLong(sixth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
                .isPositive();
    }

    @Test
    void windowResetsAfterExpiry_allowsAgain() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String token = pilotToken(sub);
        for (int i = 0; i < 5; i++) {
            submit(token, "NOPE" + i + "AB", null);
        }
        assertThat(submit(token, "NOPE5XYZ", null).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Advance past the 15-min window — the earliest attempt ages out.
        clock.advance(Duration.ofMinutes(16));

        assertThat(submit(token, "NOPE6XYZ", null).getStatusCode())
                .as("window reset — an unknown-code 404 again, not a 429")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- deny cooldown: 24h per (sub, club) ----

    @Test
    void deniedPair_reSubmitWithin24h_is_429() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        denyAFreshRequest(sub);

        clock.advance(Duration.ofHours(1));
        ResponseEntity<String> res = submit(pilotToken(sub), codeA, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    @Test
    void deniedPair_reSubmitAfter24h_isAllowed() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        denyAFreshRequest(sub);

        clock.advance(Duration.ofHours(25));
        assertThat(submit(pilotToken(sub), codeA, null).getStatusCode())
                .as("cooldown elapsed — a fresh pending request is filed")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void denyCooldown_survivesJoinCodeRotation() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        denyAFreshRequest(sub);

        // Admin rotates the club's code via the real endpoint — a different
        // discovery key, same club.
        ResponseEntity<String> rotated = post(adminToken(clubA, adminSubA),
                "/api/v1/clubs/clb-" + clubA + "/join-code/rotate", null);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rotatedCode = readJson(rotated).get("joinCode").asText();
        assertThat(rotatedCode).isNotEqualTo(codeA);

        clock.advance(Duration.ofHours(1));
        ResponseEntity<String> res = submit(pilotToken(sub), rotatedCode, null);
        assertThat(res.getStatusCode())
                .as("cooldown is keyed on (sub, club), not on the code")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ---- withdraw starts NO cooldown ----

    @Test
    void withdraw_allowsImmediateReSubmit() {
        UUID sub = UuidCreator.getTimeOrderedEpoch();
        String id = readJson(submit(pilotToken(sub), codeA, null)).get("id").asText();
        assertThat(post(pilotToken(sub), "/api/v1/join-requests/" + id + "/withdraw", null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // No clock advance — a withdraw starts no cooldown, re-submit is immediate.
        assertThat(submit(pilotToken(sub), codeA, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    // ---- helpers ----

    private void denyAFreshRequest(UUID sub) {
        String id = readJson(submit(pilotToken(sub), codeA, null)).get("id").asText();
        ResponseEntity<String> denied =
                deny(adminToken(clubA, adminSubA), id, "not this year");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(denied).get("status").asText()).isEqualTo("DENIED");
    }

    private String pilotToken(UUID sub) {
        return jwts.mint(c -> c
                .subject(sub.toString())
                .claim("email", "pilot-" + sub + "@example.com")
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

    private void seedUser(UUID sub, UUID clubId, String username) {
        UUID languageId = jdbc.queryForObject("SELECT id FROM t_language LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO t_user (id, club_id, username, friendly_name, "
                        + "notification_email, language_id, keycloak_sub, created_on, modified_on) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid, now(), now())",
                UuidCreator.getTimeOrderedEpoch().toString(), clubId.toString(),
                username + "-" + sub, "Seed " + username, username + "@example.com",
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

    private ResponseEntity<String> deny(String token, String reqId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null) {
            body.put("reason", reason);
        }
        return post(token, "/api/v1/join-requests/" + reqId + "/deny", body);
    }

    private ResponseEntity<String> post(String token, String path, Map<String, Object> body) {
        RequestEntity.BodyBuilder b = RequestEntity.post(URI.create(path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        return rest.exchange(body == null ? b.build() : b.body(body), String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse: " + res.getBody(), e);
        }
    }
}

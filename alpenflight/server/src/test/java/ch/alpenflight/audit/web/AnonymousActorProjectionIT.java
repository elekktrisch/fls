package ch.alpenflight.audit.web;

import static ch.alpenflight.audit.web.AuditTestSupport.truncateForTenant;
import static ch.alpenflight.audit.web.AuditTestSupport.withBearer;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.PublicSubmissions;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AnonymousActorProjectionIT extends PostgresIntegrationTest {

    private static final String PUBLIC_REGISTRATION_ENTITY = "PublicFlightRegistration";
    private static final String CLUB_ENTITY = "Club";
    private static final LocalDate BOOKABLE_DAY = LocalDate.of(2099, 6, 15);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired DiscoveryFlightDayRepository discoveryDays;

    private UUID clubId;
    private String clubSlug;
    private UUID adminSub;
    private String adminToken;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_AAP_", "IT_AAP_");
        fixture.seed();
        clubId = fixture.clubA();

        Club club = clubs.findActiveById(clubId).orElseThrow();
        club.enablePublicRegistration();
        clubs.save(club);
        clubSlug = Objects.requireNonNull(club.getSlug(), "fixture club has no slug");
        TenantTestContext.runAs(clubId, () ->
                discoveryDays.save(DiscoveryFlightDay.schedule(BOOKABLE_DAY, BOOKABLE_DAY)));
        truncateForTenant(jdbc, clubId);

        adminSub = UUID.randomUUID();
        adminToken = jwts.mintJitReady(adminSub, clubId, c -> c
                .claim("preferred_username", "audit-actor-admin-" + adminSub)
                .claim("locale", "en")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @AfterEach
    void dropMintedPrincipal() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE actor_keycloak_sub = ?",
                adminSub.toString());
        jdbc.update("DELETE FROM t_user WHERE keycloak_sub = ?::uuid", adminSub.toString());
    }

    @Test
    void an_anonymous_submission_reads_as_a_system_actor_and_a_user_mutation_does_not()
            throws Exception {
        submitAnonymously();
        updateClubAsAdmin();

        JsonNode page = listAuditEvents();

        JsonNode anonymous = rowWithTarget(page, PUBLIC_REGISTRATION_ENTITY);
        assertThat(anonymous.get("systemActor").asBoolean())
                .as("the anonymous submission takes the viewer's system-actor branch")
                .isTrue();
        assertThat(text(anonymous, "actorUserId")).isNull();
        assertThat(text(anonymous, "actorKeycloakSub")).isNull();
        assertThat(text(anonymous, "tenantClubId"))
                .as("and is scoped to the club the slug named")
                .isEqualTo(clubId.toString());

        JsonNode authenticated = rowWithTarget(page, CLUB_ENTITY);
        assertThat(authenticated.get("systemActor").asBoolean())
                .as("a club-admin mutation in the same page is NOT a system actor")
                .isFalse();
        assertThat(text(authenticated, "actorKeycloakSub")).isEqualTo(adminSub.toString());
        assertThat(text(authenticated, "actorUserId"))
                .as("the viewer's other branch renders actorUserId, so it has to be populated")
                .isNotNull();
    }

    @Test
    void actor_kind_does_not_separate_the_two_rows() {
        submitAnonymously();
        updateClubAsAdmin();

        Map<String, String> kinds = actorKindByTargetEntityType();

        assertThat(kinds)
                .as("actor_kind reads the same on both rows, which is why the projection "
                        + "omits it and the viewer keys on system_actor instead")
                .containsEntry(PUBLIC_REGISTRATION_ENTITY, "NORMAL")
                .containsEntry(CLUB_ENTITY, "NORMAL");
    }

    private void submitAnonymously() {
        ResponseEntity<Void> res = rest.postForEntity(
                "/api/v1/public/clubs/" + clubSlug + "/discovery-flight-registrations",
                PublicSubmissions.discoveryBody(BOOKABLE_DAY), Void.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void updateClubAsAdmin() {
        Club club = clubs.findActiveById(clubId).orElseThrow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", club.getClubname());
        payload.put("slug", clubSlug);
        payload.put("publicRegistrationEnabled", true);
        payload.put("countryId", Objects.requireNonNull(club.getCountryId()).toString());
        payload.put("clubStateId", Objects.requireNonNull(club.getClubStateId()).toString());

        ResponseEntity<String> res = rest.exchange(
                withBearer(RequestEntity.put(URI.create("/api/v1/clubs/" + ClubId.of(clubId)))
                        .contentType(MediaType.APPLICATION_JSON), adminToken).body(payload),
                String.class);
        assertThat(res.getStatusCode()).as("club update: %s", res.getBody()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode listAuditEvents() throws Exception {
        ResponseEntity<String> res = rest.exchange(
                withBearer(RequestEntity.get(URI.create("/api/v1/admin/audit-events")), adminToken)
                        .build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return AuditTestSupport.JSON.readTree(res.getBody());
    }

    private Map<String, String> actorKindByTargetEntityType() {
        Map<String, String> kinds = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT target_entity_type, actor_kind FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid",
                clubId.toString())) {
            kinds.put((String) row.get("target_entity_type"), (String) row.get("actor_kind"));
        }
        return kinds;
    }

    private static JsonNode rowWithTarget(JsonNode page, String targetEntityType) {
        for (JsonNode row : page.get("items")) {
            if (targetEntityType.equals(row.get("targetEntityType").asText())) {
                return row;
            }
        }
        throw new AssertionError(
                "no " + targetEntityType + " row in the projection page: " + page);
    }

    private static @Nullable String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

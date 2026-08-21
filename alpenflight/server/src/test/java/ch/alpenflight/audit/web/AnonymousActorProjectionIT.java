package ch.alpenflight.audit.web;

import static ch.alpenflight.audit.web.AuditTestSupport.preCleanAuditRowsThatOutliveTestRollback;
import static ch.alpenflight.audit.web.AuditTestSupport.withBearer;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import ch.alpenflight.planning.application.PlanningDayNotificationJob;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.platform.tenancy.Tenants;
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

    private static final String ANONYMOUS_PUBLIC_ENTITY = "PublicFlightRegistration";
    private static final String AUTHENTICATED_ENTITY = "Club";
    private static final String SCHEDULED_JOB_ENTITY = "PlanningNotificationRun";
    private static final LocalDate BOOKABLE_DAY = LocalDate.of(2099, 6, 15);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired DiscoveryFlightDayRepository discoveryDays;
    @Autowired PlanningDayNotificationJob planningDayNotifications;

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
        preCleanAuditRowsThatOutliveTestRollback(jdbc, clubId);

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
    void actor_kind_separates_the_anonymous_registration_the_authenticated_write_and_the_job() {
        submitAnonymously();
        updateClubAsAdmin();
        runTheScheduledJobForThisClub();

        Map<String, Map<String, Object>> rows = auditRowsByTargetEntityType();

        assertThat(rows.get(ANONYMOUS_PUBLIC_ENTITY))
                .as("an anonymous internet registration")
                .containsEntry("actor_kind", "ANONYMOUS_PUBLIC")
                .containsEntry("system_actor", false)
                .containsEntry("actor_user_id", null)
                .containsEntry("actor_keycloak_sub", null);

        assertThat(rows.get(AUTHENTICATED_ENTITY))
                .as("a club administrator acting on a bearer token")
                .containsEntry("actor_kind", "NORMAL")
                .containsEntry("system_actor", false)
                .containsEntry("actor_keycloak_sub", adminSub.toString());

        assertThat(rows.get(SCHEDULED_JOB_ENTITY))
                .as("a scheduled job with no principal at all")
                .containsEntry("actor_kind", "SYSTEM")
                .containsEntry("system_actor", true)
                .containsEntry("actor_user_id", null)
                .containsEntry("actor_keycloak_sub", null);
    }

    @Test
    void an_authenticated_write_records_no_client_ip_even_on_the_public_form() {
        submitAnonymously();
        updateClubAsAdmin();
        runTheScheduledJobForThisClub();
        submitTheSamePublicFormCarryingTheAdminBearerToken();

        List<Map<String, Object>> publicFormRows = jdbc.queryForList(
                "SELECT actor_kind, client_ip FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid AND target_entity_type = ? "
                        + "ORDER BY occurred_at ASC",
                clubId.toString(), ANONYMOUS_PUBLIC_ENTITY);
        assertThat(publicFormRows).hasSize(2);
        assertThat(publicFormRows.get(0)).containsEntry("actor_kind", "ANONYMOUS_PUBLIC");
        assertThat(publicFormRows.get(0).get("client_ip"))
                .as("the abuse guard's subject is on the row it acted on")
                .isNotNull();
        assertThat(publicFormRows.get(1)).containsEntry("actor_kind", "NORMAL");
        assertThat(publicFormRows.get(1).get("client_ip"))
                .as("the same form with a bearer token names the actor and keeps no client IP")
                .isNull();

        List<Map<String, Object>> ipCarryingRowsOfAnotherKind = jdbc.queryForList(
                "SELECT id, actor_kind FROM t_mutation_audit_event "
                        + "WHERE client_ip IS NOT NULL AND actor_kind <> 'ANONYMOUS_PUBLIC'");
        assertThat(ipCarryingRowsOfAnotherKind)
                .as("no row of any other actor kind, anywhere in the table, carries a client IP")
                .isEmpty();
    }

    @Test
    void the_projection_publishes_the_actor_kind_and_never_the_client_ip() throws Exception {
        submitAnonymously();
        updateClubAsAdmin();

        JsonNode page = listAuditEvents();

        JsonNode anonymous = rowWithTarget(page, ANONYMOUS_PUBLIC_ENTITY);
        assertThat(text(anonymous, "actorKind")).isEqualTo("ANONYMOUS_PUBLIC");
        assertThat(anonymous.get("systemActor").asBoolean()).isFalse();
        assertThat(text(anonymous, "actorUserId")).isNull();
        assertThat(text(anonymous, "tenantClubId"))
                .as("and is scoped to the club the slug named")
                .isEqualTo(clubId.toString());
        assertThat(anonymous.has("clientIp"))
                .as("the admin projection carries no client IP; the raw value stays in the table")
                .isFalse();

        JsonNode authenticated = rowWithTarget(page, AUTHENTICATED_ENTITY);
        assertThat(text(authenticated, "actorKind")).isEqualTo("NORMAL");
        assertThat(authenticated.get("systemActor").asBoolean()).isFalse();
        assertThat(text(authenticated, "actorKeycloakSub")).isEqualTo(adminSub.toString());
        assertThat(text(authenticated, "actorUserId")).isNotNull();
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

    private void submitTheSamePublicFormCarryingTheAdminBearerToken() {
        ResponseEntity<String> res = rest.exchange(
                withBearer(RequestEntity
                        .post(URI.create(
                                "/api/v1/public/clubs/" + clubSlug + "/scenic-flight-registrations"))
                        .contentType(MediaType.APPLICATION_JSON), adminToken)
                        .body(PublicSubmissions.scenicBody()),
                String.class);
        assertThat(res.getStatusCode())
                .as("authenticated scenic submission: %s", res.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private void runTheScheduledJobForThisClub() {
        Tenants.runAs(clubId, planningDayNotifications::runForCurrentClub);
    }

    private JsonNode listAuditEvents() throws Exception {
        ResponseEntity<String> res = rest.exchange(
                withBearer(RequestEntity.get(URI.create("/api/v1/admin/audit-events")), adminToken)
                        .build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return AuditTestSupport.JSON.readTree(res.getBody());
    }

    private Map<String, Map<String, Object>> auditRowsByTargetEntityType() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT target_entity_type, actor_kind, system_actor, actor_user_id, "
                        + "actor_keycloak_sub, client_ip FROM t_mutation_audit_event "
                        + "WHERE tenant_club_id = ?::uuid "
                        + "AND target_entity_type IN (?, ?, ?)",
                clubId.toString(),
                ANONYMOUS_PUBLIC_ENTITY, AUTHENTICATED_ENTITY, SCHEDULED_JOB_ENTITY);
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byType.put((String) row.get("target_entity_type"), row);
        }
        assertThat(byType.keySet())
                .containsExactlyInAnyOrder(
                        ANONYMOUS_PUBLIC_ENTITY, AUTHENTICATED_ENTITY, SCHEDULED_JOB_ENTITY);
        assertThat(rows)
                .as("the three writes each produced exactly one row in this tenant")
                .hasSize(3);
        return byType;
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

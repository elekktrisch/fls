package ch.alpenflight.accounting.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class AccountingRuleFiltersControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/accounting-rule-filters";

    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";
    private static final String OTHER_CLUB_ID = "019e30c3-2c00-7001-8000-0000000000ff";

    private static final String FILTER_TYPE_FLIGHT_TIME = "019e2e15-2c00-7652-8000-000000004652";
    private static final int LEGACY_FLIGHT_TIME = 30;

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String clubAdminToken;
    private String otherClubAdminToken;

    @BeforeEach
    void mintTokensAndClean() {
        clubAdminToken = adminTokenFor(CLUB_ID);
        otherClubAdminToken = adminTokenFor(OTHER_CLUB_ID);
        jdbc.update("DELETE FROM t_accounting_rule_filter WHERE operating_club_id = ?::uuid", CLUB_ID);
    }

    @Test
    void list_returns_200_with_empty_array_initially() {
        ResponseEntity<String> res = get(BASE, clubAdminToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isZero();
    }

    @Test
    void create_valid_returns_201_with_location_then_get_round_trips() {
        ResponseEntity<String> created = post(BASE, articlePayload("Landing fee rule"), clubAdminToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(created);
        String id = body.get("id").asText();
        assertThat(body.get("ruleFilterName").asText()).isEqualTo("Landing fee rule");
        assertThat(body.get("articleTarget").asText()).isEqualTo("ART-100");
        JsonNode recipient = body.path("recipientTarget");
        assertThat(recipient.isMissingNode() || recipient.isNull()).isTrue();

        URI loc = created.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo(BASE + "/" + id);

        ResponseEntity<String> got = get(BASE + "/" + id, clubAdminToken);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(got).get("ruleFilterName").asText()).isEqualTo("Landing fee rule");
        assertThat(readJson(got).get("filterConfig").get("isRuleForGliderFlights").asBoolean()).isTrue();
    }

    @Test
    void list_includes_created_filter_with_computed_target() {
        post(BASE, articlePayload("Listed rule"), clubAdminToken);
        ResponseEntity<String> res = get(BASE, clubAdminToken);
        JsonNode body = readJson(res);
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).get("ruleFilterName").asText()).isEqualTo("Listed rule");
        assertThat(body.get(0).get("target").asText()).isEqualTo("ART-100 (Landing fee line)");
    }

    @Test
    void update_renames_and_returns_200() {
        ResponseEntity<String> created = post(BASE, articlePayload("Before"), clubAdminToken);
        String id = readJson(created).get("id").asText();

        Map<String, Object> upd = articlePayload("After");
        ResponseEntity<String> res = put(BASE + "/" + id, upd, clubAdminToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(res).get("ruleFilterName").asText()).isEqualTo("After");
    }

    @Test
    void delete_returns_204_then_get_is_404() {
        ResponseEntity<String> created = post(BASE, articlePayload("Doomed"), clubAdminToken);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> del = delete(BASE + "/" + id, clubAdminToken);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> got = get(BASE + "/" + id, clubAdminToken);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_unknownId_returns_404() {
        ResponseEntity<String> res = get(BASE + "/019e30c3-2c00-7001-8000-000000000aaa", clubAdminToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void crossTenant_get_returns_404() {
        ResponseEntity<String> created = post(BASE, articlePayload("Club 1 private"), clubAdminToken);
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> res = get(BASE + "/" + id, otherClubAdminToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void create_blankName_returns_400() {
        Map<String, Object> body = articlePayload("ignored");
        body.put("ruleFilterName", "  ");
        ResponseEntity<String> res = post(BASE, body, clubAdminToken);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_omittingOptionalBooleans_returns_201_with_legacy_defaults() {
        Map<String, Object> body = articlePayload("No-flags rule");
        body.remove("active");
        body.remove("stopRuleEngineWhenApplied");
        body.remove("chargedToClubInternal");

        ResponseEntity<String> created = post(BASE, body, clubAdminToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String id = readJson(created).get("id").asText();
        ResponseEntity<String> got = get(BASE + "/" + id, clubAdminToken);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = readJson(got);
        assertThat(detail.get("active").asBoolean()).isTrue();
        assertThat(detail.get("stopRuleEngineWhenApplied").asBoolean()).isFalse();
        assertThat(detail.get("chargedToClubInternal").asBoolean()).isFalse();
    }


    private static Map<String, Object> articlePayload(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("filterTypeId", FILTER_TYPE_FLIGHT_TIME);
        body.put("filterTypeLegacyId", LEGACY_FLIGHT_TIME);
        body.put("ruleFilterName", name);
        body.put("description", "desc");
        body.put("active", true);
        body.put("stopRuleEngineWhenApplied", false);
        body.put("chargedToClubInternal", false);
        body.put("articleNumber", "ART-100");
        body.put("deliveryLineText", "Landing fee line");
        body.put("filterConfig", filterConfig());
        return body;
    }

    private static Map<String, Object> filterConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("isRuleForGliderFlights", true);
        cfg.put("isRuleForTowingFlights", false);
        cfg.put("isRuleForMotorFlights", false);
        cfg.put("noLandingTaxForGlider", false);
        cfg.put("noLandingTaxForTowingAircraft", false);
        cfg.put("noLandingTaxForAircraft", false);
        cfg.put("includeFlightTypeName", false);
        cfg.put("extendMatchingFlightTypeCodesToGliderAndTowFlight", false);
        cfg.put("includeThresholdText", false);
        return cfg;
    }


    private String adminTokenFor(String clubId) {
        return jwts.mint(c -> c
                .claim("clubId", clubId)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String path, Object body, String token) {
        return rest.exchange(
                RequestEntity.put(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        return rest.exchange(
                RequestEntity.delete(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build(),
                String.class);
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}

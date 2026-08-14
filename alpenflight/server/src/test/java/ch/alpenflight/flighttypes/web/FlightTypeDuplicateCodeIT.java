package ch.alpenflight.flighttypes.web;

import static ch.alpenflight.flighttypes.web.FlightTypesTestFixtures.createPayload;
import static ch.alpenflight.flighttypes.web.FlightTypesTestFixtures.uniqueName;
import static ch.alpenflight.flighttypes.web.FlightTypesTestFixtures.updatePayload;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
class FlightTypeDuplicateCodeIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private String clubAdminToken;

    @BeforeEach
    void seedClubsAndMintToken() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_FTDC_", "IT_FTDC");
        fixture.seed();
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", fixture.clubA().toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
    }

    @Test
    void registerFlightType_duplicateCode_returns409OnFlightCodeField() {
        ResponseEntity<String> first = post(payloadWithCode(uniqueName(), "DUPC"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> dup = post(payloadWithCode(uniqueName(), "DUPC"));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = readJson(dup);
        assertThat(body.get("type").asText())
                .isEqualTo("urn:alpenflight:problem:flight-type-code-conflict");
        assertThat(body.get("title").asText()).contains("code already in use");
        assertThat(body.get("field").asText()).isEqualTo("flightCode");
    }

    @Test
    void updateFlightType_toAnotherActiveRowsCode_returns409_ownCodeStaysAllowed() {
        ResponseEntity<String> a = post(payloadWithCode(uniqueName(), "C1"));
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String nameB = uniqueName();
        ResponseEntity<String> b = post(payloadWithCode(nameB, "C2"));
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String idB = readJson(b).get("id").asText();

        Map<String, Object> keepOwn = updatePayload(nameB);
        keepOwn.put("flightCode", "C2");
        assertThat(put(idB, keepOwn).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> takeOther = updatePayload(nameB);
        takeOther.put("flightCode", "C1");
        ResponseEntity<String> conflict = put(idB, takeOther);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = readJson(conflict);
        assertThat(body.get("field").asText()).isEqualTo("flightCode");
        assertThat(body.get("type").asText())
                .isEqualTo("urn:alpenflight:problem:flight-type-code-conflict");
    }


    private static Map<String, Object> payloadWithCode(String name, String code) {
        Map<String, Object> payload = createPayload(name);
        payload.put("flightCode", code);
        return payload;
    }

    private ResponseEntity<String> post(Object body) {
        return rest.exchange(
                RequestEntity.post(URI.create("/api/v1/flight-types"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String id, Object body) {
        return rest.exchange(
                RequestEntity.put(URI.create("/api/v1/flight-types/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .body(body),
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

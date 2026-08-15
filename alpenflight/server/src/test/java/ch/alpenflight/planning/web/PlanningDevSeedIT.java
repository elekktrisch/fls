package ch.alpenflight.planning.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(JwtTestFixture.class)
class PlanningDevSeedIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID SEED_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final String WEEKDAY_DAY_ID = "019e30c3-2c00-7001-8000-000000000e01";
    private static final String WEEKEND_DAY_ID = "019e30c3-2c00-7001-8000-000000000e02";
    private static final String INSTRUCTOR_ID = "pn-019e30c3-2c00-7001-8000-0000000000b1";
    private static final String TOWPILOT_ID = "pn-019e30c3-2c00-7001-8000-0000000000b2";
    private static final String FLIGHTOP_ID = "pn-019e30c3-2c00-7001-8000-0000000000b3";

    @Autowired TestRestTemplate rest;
    @Autowired JwtTestFixture jwts;

    @Test
    void devSeed_futureList_rendersSeededDays_withCrewOnTheWeekdayDay() {
        String token = jwts.mint(c -> c
                .claim("clubId", SEED_CLUB.toString())
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));

        ResponseEntity<String> res = rest.exchange(
                RequestEntity.get(URI.create("/api/v1/planning-days/overview/future"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).build(),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode list = readJson(res);
        assertThat(list.isArray()).isTrue();

        JsonNode weekday = findById(list, WEEKDAY_DAY_ID);
        JsonNode weekend = findById(list, WEEKEND_DAY_ID);
        assertThat(weekday).as("seeded weekday planning day present in the future list").isNotNull();
        assertThat(weekend).as("seeded weekend planning day present in the future list").isNotNull();

        assertThat(weekday.get("instructorPersonId").asText()).isEqualTo(INSTRUCTOR_ID);
        assertThat(weekday.get("towingPilotPersonId").asText()).isEqualTo(TOWPILOT_ID);
        assertThat(weekday.get("flightOperatorPersonId").asText()).isEqualTo(FLIGHTOP_ID);
        assertThat(weekday.get("locationId").asText())
                .isEqualTo("loc-019e30c3-2c00-7001-8000-00000000c001");

        assertThat(weekend.path("instructorPersonId").isNull()
                || weekend.path("instructorPersonId").isMissingNode())
                .as("the seeded weekend day is bare — no crew, exercising the empty-crew render path")
                .isTrue();
        assertThat(weekend.path("towingPilotPersonId").isNull()
                || weekend.path("towingPilotPersonId").isMissingNode()).isTrue();
        assertThat(weekend.path("flightOperatorPersonId").isNull()
                || weekend.path("flightOperatorPersonId").isMissingNode()).isTrue();

        LocalDate weekendDate = LocalDate.parse(weekend.get("planningDate").asText());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(weekendDate.getDayOfWeek())
                .as("the seeded weekend day is a Saturday (the weekend-flag render path)")
                .isEqualTo(DayOfWeek.SATURDAY);
        assertThat(weekendDate)
                .as("the seeded weekend day is never the notification job's day+1 "
                        + "(else a stray planningday-cancel hits the shared club address)")
                .isAfter(today.plusDays(1));
    }

    private static JsonNode findById(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
        }
        return null;
    }

    private static JsonNode readJson(ResponseEntity<String> res) {
        try {
            return MAPPER.readTree(res.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response: " + res.getBody(), e);
        }
    }
}

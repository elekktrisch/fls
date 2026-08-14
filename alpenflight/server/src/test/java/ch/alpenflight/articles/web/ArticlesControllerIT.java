package ch.alpenflight.articles.web;

import static ch.alpenflight.articles.web.ArticlesTestFixtures.createPayload;
import static ch.alpenflight.articles.web.ArticlesTestFixtures.uniqueNumber;
import static ch.alpenflight.articles.web.ArticlesTestFixtures.updatePayload;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.platform.security.JwtTestFixture;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
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
class ArticlesControllerIT extends PostgresIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUB_ID = "019e30c3-2c00-7001-8000-000000000001";

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTestFixture jwts;

    private String clubAdminToken;

    @BeforeEach
    void mintClubAdminTokenAndCleanArticles() {
        clubAdminToken = jwts.mint(c -> c
                .claim("clubId", CLUB_ID)
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR"))));
        jdbc.update("DELETE FROM t_article WHERE operating_club_id = ?::uuid", CLUB_ID);
    }

    @Test
    void listArticles_returns_200_with_empty_array_initially() {
        ResponseEntity<String> res = get("/api/v1/articles");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(res);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isZero();
    }

    @Test
    void registerArticle_valid_returns_201_with_location_header() {
        String number = uniqueNumber();
        ResponseEntity<String> res = post("/api/v1/articles", createPayload(number));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = readJson(res);
        assertThat(body.get("articleNumber").asText()).isEqualTo(number);
        assertThat(body.get("isActive").asBoolean()).isTrue();
        URI loc = res.getHeaders().getLocation();
        assertThat(loc).isNotNull();
        assertThat(loc.getPath()).isEqualTo("/api/v1/articles/" + body.get("id").asText());
    }

    @Test
    void registerArticle_duplicateNumber_returns_409() {
        String number = uniqueNumber();
        ResponseEntity<String> first = post("/api/v1/articles", createPayload(number));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> dup = post("/api/v1/articles", createPayload(number));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = readJson(dup);
        assertThat(body.get("title").asText()).contains("already in use");
        assertThat(body.get("field").asText()).isEqualTo("articleNumber");
    }

    @Test
    void updateArticle_renames_andRenumbers() {
        String original = uniqueNumber();
        ResponseEntity<String> created = post("/api/v1/articles", createPayload(original));
        String id = readJson(created).get("id").asText();

        String renamed = uniqueNumber();
        ResponseEntity<String> upd = put("/api/v1/articles/" + id, updatePayload(renamed));
        assertThat(upd.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = readJson(upd);
        assertThat(body.get("articleNumber").asText()).isEqualTo(renamed);
        assertThat(body.get("articleName").asText()).isEqualTo("Glider hour (renamed)");
    }

    @Test
    void softDelete_thenRecreateSameNumber_succeeds() {
        String number = uniqueNumber();
        ResponseEntity<String> created = post("/api/v1/articles", createPayload(number));
        String id = readJson(created).get("id").asText();

        ResponseEntity<String> del = delete("/api/v1/articles/" + id);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> recreated = post("/api/v1/articles", createPayload(number));
        assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listArticles_excludesInactiveByDefault_butSurfacesWithFlag() {
        String activeNumber = uniqueNumber();
        post("/api/v1/articles", createPayload(activeNumber));
        String inactiveNumber = uniqueNumber();
        Map<String, Object> inactivePayload = createPayload(inactiveNumber);
        inactivePayload.put("isActive", false);
        post("/api/v1/articles", inactivePayload);

        JsonNode defaultList = readJson(get("/api/v1/articles"));
        assertThat(toNumbers(defaultList))
                .contains(activeNumber)
                .doesNotContain(inactiveNumber);

        JsonNode fullList = readJson(get("/api/v1/articles?includeInactive=true"));
        assertThat(toNumbers(fullList)).contains(activeNumber, inactiveNumber);
    }

    @Test
    void listArticles_excludesSoftDeleted_evenWithIncludeInactiveFlag() {
        String number = uniqueNumber();
        ResponseEntity<String> created = post("/api/v1/articles", createPayload(number));
        String id = readJson(created).get("id").asText();
        ResponseEntity<String> del = delete("/api/v1/articles/" + id);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode fullList = readJson(get("/api/v1/articles?includeInactive=true"));
        assertThat(toNumbers(fullList)).doesNotContain(number);
    }

    @Test
    void getArticle_unknownId_returns_404() {
        ResponseEntity<String> res = get(
                "/api/v1/articles/art-019e30c3-2c00-7001-8000-000000000aaa");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void registerArticle_blankName_returns_400() {
        Map<String, Object> body = createPayload(uniqueNumber());
        body.put("articleName", "");
        ResponseEntity<String> res = post("/api/v1/articles", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void registerArticle_missingIsActive_returns_400() {
        Map<String, Object> body = createPayload(uniqueNumber());
        body.remove("isActive");
        ResponseEntity<String> res = post("/api/v1/articles", body);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }


    private static List<String> toNumbers(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(n -> n.get("articleNumber").asText())
                .toList();
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(
                RequestEntity.get(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .build(),
                String.class);
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.exchange(
                RequestEntity.post(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> put(String path, Object body) {
        return rest.exchange(
                RequestEntity.put(URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
                        .body(body),
                String.class);
    }

    private ResponseEntity<String> delete(String path) {
        return rest.exchange(
                RequestEntity.delete(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clubAdminToken)
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

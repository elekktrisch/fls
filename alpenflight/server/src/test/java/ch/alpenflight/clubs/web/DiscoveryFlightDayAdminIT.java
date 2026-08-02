package ch.alpenflight.clubs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The club-admin discovery-flight-day surface as an admin drives it, paired
 * with the anonymous picker the days feed: a published day has to reach BOTH
 * lists and a withdrawn one has to leave both, or the two surfaces disagree
 * about what the club offers.
 *
 * <p>Every cross-tenant case is seeded adversarially — club B publishes real
 * days while club A's admin reads and writes — so a missing {@code @TenantId}
 * scope fails here instead of passing over an empty neighbour. The withdrawal
 * case additionally asserts that B's day is still there afterwards: a 404 alone
 * would also be returned by a delete that succeeded and then reported badly.
 *
 * <p>The re-publish case is the one a naive full UNIQUE breaks: withdrawal is a
 * soft delete, and V58's partial index excludes withdrawn rows, so the date
 * must be offerable again.
 */
@AutoConfigureMockMvc
class DiscoveryFlightDayAdminIT extends PostgresIntegrationTest {

    private static final String ADMIN_PATH = "/api/v1/discovery-flight-days";
    private static final LocalDate DAY = LocalDate.of(2099, 6, 15);
    private static final LocalDate SECOND_DAY = LocalDate.of(2099, 7, 20);
    private static final LocalDate PAST_DAY = LocalDate.of(2020, 6, 15);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;

    private UUID clubA;
    private UUID clubB;
    private String clubASlug;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, "IT_DFA_", "IT_DFA_");
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();

        Club open = clubs.findActiveById(clubA).orElseThrow();
        open.enablePublicRegistration();
        clubs.save(open);
        clubASlug = requireSlug(open);
    }

    @Test
    void a_published_day_reaches_both_lists_and_withdrawal_soft_deletes_it() throws Exception {
        UUID id = publish(clubA, DAY);

        assertThat(adminDays(clubA)).containsExactly(DAY);
        assertThat(publicDays(clubASlug)).containsExactly(DAY);

        mvc.perform(delete(ADMIN_PATH + "/" + id).with(clubadmin(clubA)))
                .andExpect(status().isNoContent());

        assertThat(adminDays(clubA)).isEmpty();
        assertThat(publicDays(clubASlug)).isEmpty();
        assertThat(deletedOnOf(id))
                .as("withdrawal keeps the row and stamps deleted_on — never a hard delete")
                .isNotNull();
    }

    @Test
    void a_withdrawn_date_can_be_published_again() throws Exception {
        UUID first = publish(clubA, DAY);
        mvc.perform(delete(ADMIN_PATH + "/" + first).with(clubadmin(clubA)))
                .andExpect(status().isNoContent());

        UUID second = publish(clubA, DAY);

        assertThat(second).isNotEqualTo(first);
        assertThat(adminDays(clubA)).containsExactly(DAY);
        assertThat(publicDays(clubASlug)).containsExactly(DAY);
    }

    @Test
    void a_duplicate_live_date_is_a_clean_409() throws Exception {
        publish(clubA, DAY);

        mvc.perform(post(ADMIN_PATH)
                        .with(clubadmin(clubA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dayPayload(DAY)))
                .andExpect(status().isConflict());

        assertThat(adminDays(clubA)).containsExactly(DAY);
    }

    @Test
    void a_day_in_the_past_is_refused() throws Exception {
        mvc.perform(post(ADMIN_PATH)
                        .with(clubadmin(clubA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dayPayload(PAST_DAY)))
                .andExpect(status().isBadRequest());

        assertThat(adminDays(clubA)).isEmpty();
    }

    @Test
    void an_admin_lists_only_the_own_clubs_days() throws Exception {
        publish(clubA, DAY);
        publish(clubB, SECOND_DAY);

        assertThat(adminDays(clubA)).containsExactly(DAY);
        assertThat(adminDays(clubB)).containsExactly(SECOND_DAY);
    }

    @Test
    void publishing_lands_in_the_callers_club_only() throws Exception {
        publish(clubA, DAY);

        assertThat(adminDays(clubB))
                .as("club A's day must be invisible to club B")
                .isEmpty();
        assertThat(publish(clubB, DAY))
                .as("the date is still free for club B — uniqueness is per-tenant")
                .isNotNull();
        assertThat(adminDays(clubB)).containsExactly(DAY);
    }

    @Test
    void an_admin_cannot_withdraw_another_clubs_day() throws Exception {
        UUID clubBsDay = publish(clubB, SECOND_DAY);

        mvc.perform(delete(ADMIN_PATH + "/" + clubBsDay).with(clubadmin(clubA)))
                .andExpect(status().isNotFound());

        assertThat(adminDays(clubB))
                .as("club B's day survives club A's attempt")
                .containsExactly(SECOND_DAY);
        assertThat(deletedOnOf(clubBsDay)).isNull();
    }

    @Test
    void anonymous_callers_are_turned_away_from_every_method() throws Exception {
        UUID id = publish(clubA, DAY);

        mvc.perform(get(ADMIN_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(ADMIN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dayPayload(SECOND_DAY)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(ADMIN_PATH + "/" + id)).andExpect(status().isUnauthorized());

        assertThat(adminDays(clubA)).containsExactly(DAY);
    }

    @Test
    void a_non_admin_member_is_turned_away_from_every_method() throws Exception {
        UUID id = publish(clubA, DAY);
        RequestPostProcessor pilot = role("ROLE_PILOT", clubA);

        mvc.perform(get(ADMIN_PATH).with(pilot)).andExpect(status().isForbidden());
        mvc.perform(post(ADMIN_PATH)
                        .with(pilot)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dayPayload(SECOND_DAY)))
                .andExpect(status().isForbidden());
        mvc.perform(delete(ADMIN_PATH + "/" + id).with(pilot)).andExpect(status().isForbidden());

        assertThat(adminDays(clubA)).containsExactly(DAY);
    }

    // ----- helpers -----

    private UUID publish(UUID clubId, LocalDate eventDate) throws Exception {
        String body = mvc.perform(post(ADMIN_PATH)
                        .with(clubadmin(clubId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dayPayload(eventDate)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(MAPPER.readTree(body).get("id").asText());
    }

    private List<LocalDate> adminDays(UUID clubId) throws Exception {
        String body = mvc.perform(get(ADMIN_PATH).with(clubadmin(clubId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return datesOf(body, node -> node.get("eventDate").asText());
    }

    private List<LocalDate> publicDays(String slug) throws Exception {
        String body = mvc.perform(get("/api/v1/public/clubs/" + slug + "/discovery-flight-days"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return datesOf(body, JsonNode::asText);
    }

    private static List<LocalDate> datesOf(String jsonArray, Function<JsonNode, String> reader)
            throws Exception {
        return StreamSupport.stream(MAPPER.readTree(jsonArray).spliterator(), false)
                .map(reader)
                .map(LocalDate::parse)
                .toList();
    }

    private Instant deletedOnOf(UUID id) {
        List<Instant> rows = jdbc.queryForList(
                "SELECT deleted_on FROM t_discovery_flight_day WHERE id = ?", Instant.class, id);
        assertThat(rows).as("the row must still exist — soft delete, not hard").hasSize(1);
        return rows.getFirst();
    }

    private static String dayPayload(LocalDate eventDate) throws Exception {
        return MAPPER.writeValueAsString(Map.of("eventDate", eventDate.toString()));
    }

    private static RequestPostProcessor clubadmin(UUID clubId) {
        return role("ROLE_CLUB_ADMINISTRATOR", clubId);
    }

    private static RequestPostProcessor role(String authority, UUID clubId) {
        return jwt()
                .jwt(t -> t.claim("clubId", clubId.toString()))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    private static String requireSlug(Club club) {
        String slug = club.getSlug();
        if (slug == null) {
            throw new IllegalStateException("Seeded club has no slug");
        }
        return slug;
    }
}

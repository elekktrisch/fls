package ch.alpenflight.emailtemplates.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.emailtemplates.domain.EmailTemplate;
import ch.alpenflight.emailtemplates.domain.EmailTemplateRepository;
import ch.alpenflight.planning.application.PlanningEmailModels.PlanningDayInfoModel;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class EmailTemplateDbResolverIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000ab302");
    private static final String TEMPLATE_KEY = "planningday-ok";
    private static final String LOCALE = "de";

    @Autowired TemplatedMailService mail;
    @Autowired EmailTemplateRepository overrides;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanOverrides() {
        seedClubB();
        jdbc.update("DELETE FROM t_email_template WHERE club_id IN (?::uuid, ?::uuid)",
                CLUB_A.toString(), CLUB_B.toString());
    }

    @Test
    void clubOverride_winsOverFileDefault() {
        Tenants.runAs(CLUB_A, () ->
                overrides.save(EmailTemplate.customize(
                        TEMPLATE_KEY, LOCALE, "Custom subject", "<p>Overridden body</p>")));

        String html = Tenants.runAs(CLUB_A, () -> mail.render(TEMPLATE_KEY, sampleModel()));

        assertThat(html).contains("Overridden body").doesNotContain("Flugbetriebstag");
    }

    @Test
    void noOverride_fallsThroughToFileDefault() {
        String html = Tenants.runAs(CLUB_A, () -> mail.render(TEMPLATE_KEY, sampleModel()));

        assertThat(html).contains("Flugbetriebstag");
    }

    @Test
    void overrideDoesNotLeakAcrossTenants() {
        Tenants.runAs(CLUB_A, () ->
                overrides.save(EmailTemplate.customize(
                        TEMPLATE_KEY, LOCALE, "A subject", "<p>Club A override</p>")));

        String htmlB = Tenants.runAs(CLUB_B, () -> mail.render(TEMPLATE_KEY, sampleModel()));

        assertThat(htmlB).contains("Flugbetriebstag").doesNotContain("Club A override");
    }

    private static Map<String, Object> sampleModel() {
        return new PlanningDayInfoModel(
                LocalDate.of(2026, 1, 1), "Bern-Belp", null, List.of(), List.of()).asModel();
    }

    private void seedClubB() {
        UUID countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        UUID clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);
        jdbc.update("""
                INSERT INTO t_club (id, clubname, club_key, country_id, club_state_id, slug, public_registration_enabled)
                VALUES (?::uuid, ?, ?, ?::uuid, ?::uuid, ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                CLUB_B.toString(), "Email Resolver IT Club B", "EML_RES_B",
                countryId.toString(), clubStateId.toString(), "email-resolver-it-b");
    }
}

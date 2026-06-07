package ch.alpenflight.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.planning.application.PlanningEmailModels.PlanningDayAssignmentModel;
import ch.alpenflight.planning.application.PlanningEmailModels.PlanningDayInfoModel;
import ch.alpenflight.planning.application.PlanningEmailModels.PlanningDayInfoModel.CrewLine;
import ch.alpenflight.platform.mail.CapturedMailSender;
import ch.alpenflight.platform.mail.MailMessage;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Renders the three J-6 T-10b planning-notification templates against the REAL
 * Spring-autoconfigured Thymeleaf engine (the T-10a {@link TemplatedMailService}
 * send-path) with representative models, then asserts the captured outbox carries
 * the expected subject + body tokens (date, location, person name, crew, sender).
 *
 * <p>One IT covers all three templates — a per-template render test would be
 * overkill (J-6 T-10b test budget). Uses the {@link CapturedMailSender}
 * {@code @Primary} fake, so no live SMTP / mailpit is needed.
 */
@Import(CapturedMailSender.Config.class)
class PlanningEmailTemplatesIT extends PostgresIntegrationTest {

    @Autowired TemplatedMailService service;
    @Autowired CapturedMailSender outbox;

    @BeforeEach
    void clearOutbox() {
        outbox.clear();
    }

    @Test
    void planningday_ok_renders_date_location_crew_and_reservations() {
        PlanningDayInfoModel model =
                new PlanningDayInfoModel(
                        LocalDate.of(2026, 7, 18),
                        "Bern-Belp",
                        "Wettbewerb",
                        List.of(new CrewLine("Fluglehrer", "Ada Lovelace")),
                        List.of("HB-3000 09:00–12:00"));

        service.send("ops@club.example", "Flugbetriebstag findet statt", "planningday-ok", model.asModel());

        MailMessage sent = onlySent();
        assertThat(sent.subject()).isEqualTo("Flugbetriebstag findet statt");
        assertThat(sent.htmlBody())
                .contains("findet statt")
                .contains("18.07.2026")
                .contains("Bern-Belp")
                .contains("Fluglehrer")
                .contains("Ada Lovelace")
                .contains("HB-3000 09:00–12:00")
                .contains("Wettbewerb");
    }

    @Test
    void planningday_cancel_renders_date_location_and_the_cancel_message() {
        PlanningDayInfoModel model =
                new PlanningDayInfoModel(
                        LocalDate.of(2026, 7, 19),
                        "Thun",
                        null,
                        List.of(),
                        List.of());

        service.send(
                "ops@club.example", "Flugbetriebstag abgesagt", "planningday-cancel", model.asModel());

        MailMessage sent = onlySent();
        assertThat(sent.subject()).isEqualTo("Flugbetriebstag abgesagt");
        assertThat(sent.htmlBody())
                .contains("abgesagt")
                .contains("19.07.2026")
                .contains("Thun");
    }

    @Test
    void planningday_assignment_notification_renders_person_role_date_url_and_sender() {
        PlanningDayAssignmentModel model =
                new PlanningDayAssignmentModel(
                        LocalDate.of(2026, 7, 25),
                        "Bern-Belp",
                        "Bitte pünktlich",
                        "Grace Hopper",
                        "Schlepppilot",
                        "https://app.alpenflight.ch/planning",
                        "AlpenFlight");

        service.send(
                "grace@pilot.example",
                "Erinnerung Flugbetriebstag",
                "planningday-assignment-notification",
                model.asModel());

        MailMessage sent = onlySent();
        assertThat(sent.to()).containsExactly("grace@pilot.example");
        assertThat(sent.subject()).isEqualTo("Erinnerung Flugbetriebstag");
        assertThat(sent.htmlBody())
                .contains("Grace Hopper")
                .contains("Schlepppilot")
                .contains("25.07.2026")
                .contains("Bern-Belp")
                .contains("Bitte pünktlich")
                .contains("https://app.alpenflight.ch/planning")
                .contains("AlpenFlight");
    }

    private MailMessage onlySent() {
        assertThat(outbox.sent()).hasSize(1);
        return outbox.sent().get(0);
    }
}

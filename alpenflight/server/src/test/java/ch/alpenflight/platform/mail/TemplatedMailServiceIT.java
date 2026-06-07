package ch.alpenflight.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Proves the J-6 T-10a (ADR 0013) email send-path end-to-end against the REAL
 * Spring-autoconfigured Thymeleaf engine: {@code templates/email/smoke.html}
 * renders with a model and the rendered {@link MailMessage} reaches the
 * {@link MailSender} port — asserted against the {@link CapturedMailSender}
 * {@code @Primary} fake, so no live SMTP / mailpit is needed.
 *
 * <p>Boots the full app context (Docker-gated via {@link PostgresIntegrationTest},
 * skips cleanly when Docker is absent) so the wiring this proves —
 * {@code spring.mail.host} ⇒ {@code JavaMailSender}, thymeleaf auto-config ⇒
 * {@code TemplateEngine}, the {@code platform.mail} beans — is the production
 * wiring T-10c will build on, not a hand-assembled lookalike.
 */
@Import(CapturedMailSender.Config.class)
class TemplatedMailServiceIT extends PostgresIntegrationTest {

    @Autowired TemplatedMailService service;
    @Autowired CapturedMailSender outbox;

    @BeforeEach
    void clearOutbox() {
        outbox.clear();
    }

    @Test
    void renders_smoke_template_and_dispatches_to_outbox() {
        service.send("ops@example.com", "Smoke", "smoke", Map.of("name", "Ada"));

        assertThat(outbox.sent()).hasSize(1);
        MailMessage sent = outbox.sent().get(0);
        assertThat(sent.to()).containsExactly("ops@example.com");
        assertThat(sent.subject()).isEqualTo("Smoke");
        assertThat(sent.htmlBody())
                .contains("AlpenFlight email send-path smoke test.")
                .contains("Hallo")
                .contains("Ada");
    }

    @Test
    void renders_to_multiple_recipients() {
        service.send(
                List.of("a@example.com", "b@example.com"),
                "Smoke",
                "smoke",
                Map.of("name", "Grace"));

        assertThat(outbox.sent()).hasSize(1);
        assertThat(outbox.sent().get(0).to())
                .containsExactly("a@example.com", "b@example.com");
        assertThat(outbox.sent().get(0).htmlBody()).contains("Grace");
    }

    @Test
    void render_only_does_not_dispatch() {
        String html = service.render("smoke", Map.of("name", "Linus"));

        assertThat(html).contains("Linus");
        assertThat(outbox.sent()).isEmpty();
    }
}

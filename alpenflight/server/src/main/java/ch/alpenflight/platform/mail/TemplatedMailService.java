package ch.alpenflight.platform.mail;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Build-service for templated email (J-6 T-10a, ADR 0013). Renders a named
 * Thymeleaf template ({@code templates/email/<name>.html}) against a model map,
 * then hands the rendered {@link MailMessage} to the {@link MailSender} port.
 *
 * <p>The template name is the bare file stem under {@code templates/email/}
 * (e.g. {@code "smoke"} → {@code templates/email/smoke.html}). The subject is
 * passed by the caller — the planning templates (T-10b) will source it from
 * the i18n bundle, but subject-resolution is out of this infra task's scope.
 *
 * <p>This carries no business knowledge: the planning notification templates +
 * recipient logic are T-10b / T-10c. It exists so those tasks have a working
 * render→send seam to build on.
 */
@Service
public class TemplatedMailService {

    /** Thymeleaf template-name prefix; resolves under {@code templates/}. */
    private static final String EMAIL_TEMPLATE_PREFIX = "email/";

    private final ITemplateEngine templateEngine;
    private final MailSender mailSender;

    public TemplatedMailService(ITemplateEngine templateEngine, MailSender mailSender) {
        this.templateEngine = templateEngine;
        this.mailSender = mailSender;
    }

    /**
     * Render {@code templates/email/<templateName>.html} with {@code model},
     * then send to a single recipient.
     */
    public void send(String recipient, String subject, String templateName, Map<String, Object> model) {
        send(List.of(recipient), subject, templateName, model);
    }

    /**
     * Render {@code templates/email/<templateName>.html} with {@code model},
     * then send to every recipient.
     */
    public void send(
            List<String> recipients, String subject, String templateName, Map<String, Object> model) {
        String html = render(templateName, model);
        mailSender.send(new MailMessage(recipients, subject, html));
    }

    /** Render a template to HTML without sending — handy for tests + previews. */
    public String render(String templateName, Map<String, Object> model) {
        Context context = new Context();
        context.setVariables(model);
        return templateEngine.process(EMAIL_TEMPLATE_PREFIX + templateName, context);
    }
}

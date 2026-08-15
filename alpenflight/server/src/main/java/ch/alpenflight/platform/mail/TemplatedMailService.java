package ch.alpenflight.platform.mail;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

@Service
public class TemplatedMailService {

    public static final String EMAIL_TEMPLATE_PREFIX = "email/";

    private final ITemplateEngine templateEngine;
    private final MailSender mailSender;

    public TemplatedMailService(ITemplateEngine templateEngine, MailSender mailSender) {
        this.templateEngine = templateEngine;
        this.mailSender = mailSender;
    }

    public void send(String recipient, String subject, String templateName, Map<String, Object> model) {
        send(List.of(recipient), subject, templateName, model);
    }

    public void send(
            List<String> recipients, String subject, String templateName, Map<String, Object> model) {
        String html = render(templateName, model);
        mailSender.send(new MailMessage(recipients, subject, html));
    }

    public String render(String templateName, Map<String, Object> model) {
        Context context = new Context();
        context.setVariables(model);
        return templateEngine.process(EMAIL_TEMPLATE_PREFIX + templateName, context);
    }
}

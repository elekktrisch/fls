package ch.alpenflight.joinrequests.application;

import ch.alpenflight.joinrequests.application.JoinRequestAdminRecipients.AdminRecipient;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.platform.tenancy.Tenants;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the join-request transactional emails on each committed transition
 * (S-178): {@code admin-new-request} to the club admins on a new submit,
 * {@code pilot-approved} / {@code pilot-denied} / {@code pilot-withdrawn} to the
 * pilot on the matching decision.
 *
 * <p><strong>AFTER_COMMIT = transactional-safe.</strong> A rolled-back
 * transition sends nothing — the mail fires only once the row is durable
 * (mirrors the SSE listener + the audit AFTER_COMMIT pattern). The pilot fields
 * travel on the event; the club admins are resolved here, inside a re-established
 * {@link Tenants#runAs} window for the club, so the live submit transaction made
 * no directory round-trip.
 *
 * <p><strong>DB-override-then-file resolution.</strong> Sending through
 * {@link TemplatedMailService} routes the template name through J-11's resolver
 * chain, so a club's {@code t_email_template} override wins over the shipped file
 * default with no redeploy. The override lookup is {@code @TenantId}-scoped, so
 * the render runs inside the same {@code Tenants.runAs} window.
 */
@Component
class JoinRequestEmailListener {

    static final String TEMPLATE_ADMIN_NEW_REQUEST = "join-request-admin-new-request";
    static final String TEMPLATE_PILOT_APPROVED = "join-request-pilot-approved";
    static final String TEMPLATE_PILOT_DENIED = "join-request-pilot-denied";
    static final String TEMPLATE_PILOT_WITHDRAWN = "join-request-pilot-withdrawn";

    static final String SUBJECT_ADMIN_NEW_REQUEST = "Neue Beitrittsanfrage";
    static final String SUBJECT_PILOT_APPROVED = "Beitrittsanfrage genehmigt";
    static final String SUBJECT_PILOT_DENIED = "Beitrittsanfrage abgelehnt";
    static final String SUBJECT_PILOT_WITHDRAWN = "Beitrittsanfrage zurückgezogen";

    private static final Logger LOG = LoggerFactory.getLogger(JoinRequestEmailListener.class);

    private final TemplatedMailService mail;
    private final JoinRequestAdminRecipients adminRecipients;

    JoinRequestEmailListener(TemplatedMailService mail, JoinRequestAdminRecipients adminRecipients) {
        this.mail = mail;
        this.adminRecipients = adminRecipients;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onStatusChanged(JoinRequestStatusChangedEvent event) {
        // Best-effort, post-commit: a mail / directory outage must not surface as
        // an error on the already-committed transition.
        try {
            Tenants.runAs(event.clubId(), () -> {
                switch (event.status()) {
                    case PENDING -> mailAdminsNewRequest(event);
                    case APPROVED -> mailPilot(event, TEMPLATE_PILOT_APPROVED, SUBJECT_PILOT_APPROVED);
                    case DENIED -> mailPilot(event, TEMPLATE_PILOT_DENIED, SUBJECT_PILOT_DENIED);
                    case WITHDRAWN -> mailPilot(event, TEMPLATE_PILOT_WITHDRAWN, SUBJECT_PILOT_WITHDRAWN);
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Join-request {} email notification failed", event.requestId(), e);
        }
    }

    private void mailAdminsNewRequest(JoinRequestStatusChangedEvent event) {
        for (AdminRecipient admin : adminRecipients.forClub(event.clubId())) {
            if (admin.email() == null || admin.email().isBlank()) {
                continue;
            }
            mail.send(admin.email(), SUBJECT_ADMIN_NEW_REQUEST, TEMPLATE_ADMIN_NEW_REQUEST,
                    adminModel(event));
        }
    }

    private void mailPilot(JoinRequestStatusChangedEvent event, String template, String subject) {
        if (event.pilotEmail().isBlank()) {
            LOG.warn("Join request {} has no pilot email — skipping {} mail",
                    event.requestId(), template);
            return;
        }
        mail.send(event.pilotEmail(), subject, template, pilotModel(event));
    }

    private static Map<String, Object> adminModel(JoinRequestStatusChangedEvent event) {
        Map<String, Object> model = new HashMap<>();
        model.put("pilotName", event.pilotFriendlyName());
        model.put("pilotEmail", event.pilotEmail());
        return model;
    }

    private static Map<String, Object> pilotModel(JoinRequestStatusChangedEvent event) {
        Map<String, Object> model = new HashMap<>();
        model.put("pilotName", event.pilotFriendlyName());
        model.put("decisionReason", event.decisionReason());
        return model;
    }
}

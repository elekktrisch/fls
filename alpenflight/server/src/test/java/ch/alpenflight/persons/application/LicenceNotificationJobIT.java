package ch.alpenflight.persons.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.platform.mail.CapturedMailSender;
import ch.alpenflight.platform.mail.MailMessage;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(CapturedMailSender.Config.class)
class LicenceNotificationJobIT extends PostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private static final LocalDate INSIDE_THE_NOTIFICATION_WINDOW =
            TODAY.plusDays(LicenceNotificationJob.EXPIRY_NOTICE_WINDOW_DAYS - 30);

    private static final LocalDate OUTSIDE_THE_NOTIFICATION_WINDOW =
            TODAY.plusDays(LicenceNotificationJob.EXPIRY_NOTICE_WINDOW_DAYS + 60);

    @Autowired JdbcTemplate jdbc;
    @Autowired LicenceNotificationJob job;
    @Autowired CapturedMailSender outbox;
    @Autowired PersonRepository persons;

    private String holderMail;
    private String outsideWindowMail;

    @BeforeEach
    void clean() {
        outbox.clear();
        String run = UUID.randomUUID().toString().substring(0, 8);
        holderMail = "licence.holder." + run + "@example.com";
        outsideWindowMail = "licence.outside-window." + run + "@example.com";
    }

    @Test
    void runOnce_mailsOncePerExpiringLicence_andSkipsWhatIsNotDue() {
        seedPerson(holderMail, INSIDE_THE_NOTIFICATION_WINDOW,
                INSIDE_THE_NOTIFICATION_WINDOW, OUTSIDE_THE_NOTIFICATION_WINDOW);
        seedPerson(outsideWindowMail, null, null, OUTSIDE_THE_NOTIFICATION_WINDOW);
        seedPerson(null, INSIDE_THE_NOTIFICATION_WINDOW, null, null);

        LicenceNotificationJob.RunSummary summary = job.runOnce();

        assertThat(mailsTo(holderMail))
                .as("one mail per expiring licence, not one per person")
                .isEqualTo(2);
        assertThat(mailsTo(outsideWindowMail))
                .as("a licence outside the 60-day window is not notified")
                .isZero();
        assertThat(summary.mailCount()).isGreaterThanOrEqualTo(2);
        assertThat(summary.noAddressCount())
                .as("the address-less holder is counted as skipped, not mailed")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void runOnce_namesTheExpiringLicenceInTheSubjectAndBody() {
        seedPerson(holderMail, INSIDE_THE_NOTIFICATION_WINDOW, null, null);

        job.runOnce();

        MailMessage mail = outbox.sent().stream()
                .filter(m -> m.to().contains(holderMail))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no licence mail for " + holderMail));
        assertThat(mail.subject()).isEqualTo(LicenceNotificationJob.SUBJECT);
        assertThat(mail.htmlBody()).contains("Class 1 Medical");
    }


    private long mailsTo(String email) {
        return outbox.sent().stream().filter(m -> m.to().contains(email)).count();
    }

    private UUID seedPerson(String email, LocalDate class1, LocalDate lapl, LocalDate partM) {
        Person person = Person.register("Lizenz", "Halter-" + UUID.randomUUID(), null);
        if (email != null) {
            person.updateContact(null, null, null, null, null, null, null, null, null, null,
                    email, null, false, null, null, false);
        }
        person.updateLicences(false, false, false, true, false, false, false, false, false, false,
                null, class1, null, lapl, null, null, partM,
                false, false, false, false);
        return persons.save(person).getId().value();
    }
}

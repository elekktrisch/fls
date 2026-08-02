package ch.alpenflight.persons.application;

import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import ch.alpenflight.platform.scheduling.UnscopedScheduledJob;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Licence-expiry notifications (S-085) — mirrors legacy
 * {@code LicenceNotificationJob.cs}: every person holding a licence or medical
 * that expires within the next {@value #WITHIN_DAYS} days gets <em>one mail per
 * expiring licence</em>, at their communication address.
 *
 * <p><strong>Window.</strong> {@code expiry <= today + 60}, inclusive and with no
 * lower bound — an already-expired licence keeps being notified, which is legacy's
 * behaviour and the useful one (the holder has not renewed yet).
 *
 * <p><strong>Scope.</strong> Cross-tenant like its legacy counterpart: a licence
 * belongs to the Person, not to a club membership, so the job scans people rather
 * than iterating clubs.
 */
@Component
@MeasuredJob(name = LicenceNotificationJob.JOB_NAME,
        cron = LicenceNotificationJob.CRON,
        description = "Licence + medical expiry notifications")
public class LicenceNotificationJob implements BusinessJob {

    private static final Logger LOG = LoggerFactory.getLogger(LicenceNotificationJob.class);

    /** Stable registry key — see {@link MeasuredJob#name()}. */
    public static final String JOB_NAME = "licence-notification";

    static final String CRON = "0 0 5 1 * *";

    static final String TEMPLATE = "licence-expiry";
    static final String SUBJECT = "Ablauf Lizenz / Medical";

    /** Notification window, in days ahead of expiry ({@code :35}). */
    static final int WITHIN_DAYS = 60;

    /**
     * The six notified licences, in legacy's evaluation order, each with the
     * display name its mail carries ({@code :47-77}).
     */
    private static final List<LicenceKind> LICENCE_KINDS = List.of(
            new LicenceKind("LAPL Medical", Person::getMedicalLaplExpireDate),
            new LicenceKind("Class 1 Medical", Person::getMedicalClass1ExpireDate),
            new LicenceKind("Class 2 Medical", Person::getMedicalClass2ExpireDate),
            new LicenceKind("Segelfluglehrer-Lizenz", Person::getGliderInstructorLicenceExpireDate),
            new LicenceKind("Motorfluglehrer-Lizenz", Person::getMotorInstructorLicenceExpireDate),
            new LicenceKind("Part-M Lizenz", Person::getPartMLicenceExpireDate));

    private final PersonRepository persons;
    private final TemplatedMailService mail;
    private final Clock clock;

    public LicenceNotificationJob(PersonRepository persons,
                                  TemplatedMailService mail,
                                  Clock clock) {
        this.persons = persons;
        this.mail = mail;
        this.clock = clock;
    }

    /** Scheduled tick — monthly, matching legacy's monthly workflow slot. */
    @Scheduled(cron = CRON)
    @UnscopedScheduledJob
    public void runScheduled() {
        runOnce();
    }

    /** Cross-tenant "Run now" for the {@code /system/jobs} console. */
    @Override
    @Transactional(readOnly = true)
    public RunSummary runOnce() {
        LocalDate cutoff = LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(WITHIN_DAYS);
        int sent = 0;
        int skipped = 0;
        for (Person person : persons.findWithLicenceExpiringOnOrBefore(cutoff)) {
            String email = person.emailForCommunication();
            if (email == null || email.isBlank()) {
                skipped++;
                continue;
            }
            for (ExpiringLicence licence : expiringLicencesOf(person, cutoff)) {
                if (notify(email, person, licence)) {
                    sent++;
                }
            }
        }
        return new RunSummary(sent, skipped);
    }

    private boolean notify(String email, Person person, ExpiringLicence licence) {
        try {
            mail.send(email, SUBJECT, TEMPLATE, Map.of("licence", new LicenceExpiryModel(
                    displayName(person), licence.name(), licence.expiresOn())));
            return true;
        } catch (RuntimeException e) {
            LOG.error("licence-notification could not mail {} for {}",
                    person.getId(), licence.name(), e);
            return false;
        }
    }

    private static List<ExpiringLicence> expiringLicencesOf(Person person, LocalDate cutoff) {
        List<ExpiringLicence> expiring = new ArrayList<>();
        for (LicenceKind kind : LICENCE_KINDS) {
            LocalDate expiry = kind.expiry().apply(person);
            if (expiry != null && !expiry.isAfter(cutoff)) {
                expiring.add(new ExpiringLicence(kind.displayName(), expiry));
            }
        }
        return expiring;
    }

    private static String displayName(Person person) {
        return (person.getFirstname() + " " + person.getLastname()).strip();
    }

    private record LicenceKind(String displayName,
                               Function<Person, @Nullable LocalDate> expiry) {}

    private record ExpiringLicence(String name, LocalDate expiresOn) {}

    /** Thymeleaf binding for {@code licence-expiry.html}. */
    public record LicenceExpiryModel(String personName, String licenceName, LocalDate expiresOn) {}

    /**
     * Non-PII run summary: mails sent, and how many candidate people were skipped
     * for having no address.
     */
    public record RunSummary(int mailCount, int noAddressCount) {

        @Override
        public String toString() {
            return mailCount + " expiry mails sent, " + noAddressCount + " without address";
        }
    }
}

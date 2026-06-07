package ch.alpenflight.planning.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.locations.domain.Location;
import ch.alpenflight.locations.domain.LocationRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.planning.application.PlanningEmailModels.PlanningDayAssignmentModel;
import ch.alpenflight.planning.application.PlanningEmailModels.PlanningDayInfoModel;
import ch.alpenflight.planning.application.PlanningEmailModels.PlanningDayInfoModel.CrewLine;
import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.planning.domain.PlanningDayAssignment;
import ch.alpenflight.planning.domain.PlanningDayAssignmentType;
import ch.alpenflight.planning.domain.PlanningDayAssignmentTypeRepository;
import ch.alpenflight.planning.domain.PlanningDayRepository;
import ch.alpenflight.platform.mail.MailSettings;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planning-day notification job (J-6 S-086) — mirrors legacy
 * {@code PlanningDayNotificationJob.cs}. Two exact-date passes per club:
 *
 * <ul>
 *   <li><strong>imminent = today + 1</strong> — for a club that opts in
 *       ({@link Club#wantsPlanningDayNotifications()} — a non-blank notification
 *       address), each planning day dated exactly tomorrow sends one mail to the
 *       club's notification address: {@code planningday-ok} when the day takes
 *       place ({@link Club#shouldSendPlanningDayOk(boolean)} — has ≥1 aircraft
 *       reservation OR the club allows reservation-less days), else
 *       {@code planningday-cancel} ({@code PlanningDayNotificationJob.cs:64-94}).</li>
 *   <li><strong>week-ahead = today + 7</strong> — for each planning day dated
 *       exactly +7, every assigned person (all three roles) is mailed a
 *       {@code planningday-assignment-notification} at their communication
 *       address; a blank address is skipped. The legacy per-person opt-out flag
 *       is deliberately ignored (parity — {@code :124-163}).</li>
 * </ul>
 *
 * <p><strong>Tenant scope.</strong> The job is per-club tenant-scoped, NOT
 * {@code @UnscopedScheduledJob}: it carries {@link LifecycleStateFilter} so the
 * {@code LifecycleStateFilterAspect} runs {@link #runForCurrentClub()} once per
 * Club under each {@code ACTIVE} Deployment, with the Club's tenant context
 * already established (so the tenant-scoped reads — planning days, the club's own
 * Location replica — resolve to that club). Only {@code ACTIVE} clubs get mail;
 * {@code SANDBOX} / {@code DELETING} are excluded.
 *
 * <p><strong>ADR-0022 §2.</strong> Template + recipient <em>selection</em> lives
 * here / on the {@code Club} aggregate ({@link Club#shouldSendPlanningDayOk}),
 * never in SQL. The repo only fetches by exact date; this service decides
 * ok-vs-cancel and which people to mail.
 */
@Component
public class PlanningDayNotificationJob {

    private static final Logger LOG = LoggerFactory.getLogger(PlanningDayNotificationJob.class);

    /** Audit entity-type for the run summary (not a persisted entity → PII-safe). */
    static final String AUDIT_ENTITY_TYPE = "PlanningNotificationRun";

    /** The imminent pass fires for days dated exactly {@value} day(s) out. */
    static final int IMMINENT_OFFSET_DAYS = 1;

    /** The week-ahead pass fires for days dated exactly {@value} day(s) out. */
    static final int WEEK_AHEAD_OFFSET_DAYS = 7;

    static final String TEMPLATE_OK = "planningday-ok";
    static final String TEMPLATE_CANCEL = "planningday-cancel";
    static final String TEMPLATE_ASSIGNMENT = "planningday-assignment-notification";

    static final String SUBJECT_OK = "Flugbetriebstag findet statt";
    static final String SUBJECT_CANCEL = "Flugbetriebstag abgesagt";
    static final String SUBJECT_ASSIGNMENT = "Erinnerung: Einteilung Flugbetriebstag";

    /**
     * Deep link the assignment reminder carries. No per-deployment public-URL
     * config exists yet (ADR-0013 follow-up); a stable relative landing path is
     * parity-adequate and avoids hardcoding a host.
     */
    private static final String APP_LINK = "/planning";

    private final PlanningDayRepository planningDays;
    private final PlanningDayAssignmentTypeRepository assignmentTypes;
    private final ClubRepository clubs;
    private final PersonRepository persons;
    private final LocationRepository locations;
    private final TemplatedMailService mail;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final MailSettings mailSettings;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public PlanningDayNotificationJob(PlanningDayRepository planningDays,
                                      PlanningDayAssignmentTypeRepository assignmentTypes,
                                      ClubRepository clubs,
                                      PersonRepository persons,
                                      LocationRepository locations,
                                      TemplatedMailService mail,
                                      ClubTenantIdentifierResolver tenantResolver,
                                      MailSettings mailSettings,
                                      AuditTrail auditTrail,
                                      Clock clock) {
        this.planningDays = planningDays;
        this.assignmentTypes = assignmentTypes;
        this.clubs = clubs;
        this.persons = persons;
        this.locations = locations;
        this.mail = mail;
        this.tenantResolver = tenantResolver;
        this.mailSettings = mailSettings;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /**
     * Scheduled tick (daily, early morning). The
     * {@link LifecycleStateFilterAspect} wraps this and re-enters
     * {@link #runForCurrentClub()} once per {@code ACTIVE} Club under that Club's
     * tenant scope — so the method body itself runs per-club. The cron is a
     * sensible default; prod cadence is deploy-config (J-6 oracle).
     */
    @Scheduled(cron = "0 0 6 * * *")
    @LifecycleStateFilter({LifecycleState.ACTIVE})
    public void runScheduled() {
        runForCurrentClub();
    }

    /**
     * Runs both passes for the club in the <em>current</em> tenant context. The
     * scheduled path enters this once per club (via the aspect); the guarded
     * run-now affordance ({@code POST .../notifications/run}, dev/test) calls it
     * directly for the caller's own club. Idempotent against the data — it only
     * reads + mails, never mutates planning rows.
     *
     * @return a non-PII summary of what was sent (counts + the club id)
     */
    @Transactional
    public RunSummary runForCurrentClub() {
        UUID clubId = tenantResolver.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(clubId)) {
            LOG.debug("PlanningDayNotificationJob invoked with no tenant context — skipping");
            return RunSummary.empty(clubId);
        }
        Club club = clubs.findActiveById(clubId).orElse(null);
        if (club == null) {
            return RunSummary.empty(clubId);
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        int imminent = runImminentPass(club, today.plusDays(IMMINENT_OFFSET_DAYS));
        int weekAhead = runWeekAheadPass(today.plusDays(WEEK_AHEAD_OFFSET_DAYS));
        RunSummary summary = new RunSummary(clubId, imminent, weekAhead);
        auditTrail.record(AuditAction.PLANNING_NOTIFICATIONS_RUN,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, clubId, summary));
        LOG.debug("PlanningDayNotificationJob for club {}: {} imminent, {} week-ahead mails",
                clubId, imminent, weekAhead);
        return summary;
    }

    // ----- imminent (day+1): one club-addressed ok/cancel mail per day -----

    private int runImminentPass(Club club, LocalDate dueDate) {
        if (!club.wantsPlanningDayNotifications()) {
            return 0;
        }
        String recipient = club.getPlanningDayInfoMailTo();
        if (recipient == null || recipient.isBlank()) {
            return 0;
        }
        Map<UUID, String> typeRoleLabels = roleLabelsByTypeId();
        int sent = 0;
        for (PlanningDay day : planningDays.findActiveByDate(dueDate)) {
            boolean hasReservation = reservationCount(day) > 0;
            boolean takesPlace = club.shouldSendPlanningDayOk(hasReservation);
            PlanningDayInfoModel model = infoModel(day, typeRoleLabels, takesPlace);
            String template = takesPlace ? TEMPLATE_OK : TEMPLATE_CANCEL;
            String subject = takesPlace ? SUBJECT_OK : SUBJECT_CANCEL;
            mail.send(recipient, subject, template, model.asModel());
            sent++;
        }
        return sent;
    }

    private PlanningDayInfoModel infoModel(PlanningDay day,
                                           Map<UUID, String> typeRoleLabels,
                                           boolean takesPlace) {
        List<CrewLine> crew = new ArrayList<>();
        for (PlanningDayAssignment a : day.getAssignments()) {
            String role = typeRoleLabels.getOrDefault(a.getAssignmentTypeId(), "");
            String name = personName(a.getAssignedPersonId());
            crew.add(new CrewLine(role, name));
        }
        // The cancel mail lists no reservations (parity — only the takes-place
        // mail enumerates them); a count > 0 is summarised as one display line.
        List<String> reservations = List.of();
        if (takesPlace) {
            long count = reservationCount(day);
            if (count > 0) {
                reservations = List.of(count + " Flugzeug-Reservation(en)");
            }
        }
        return new PlanningDayInfoModel(
                requireDate(day), locationName(day.getLocationId()), day.getInfo(), crew, reservations);
    }

    // ----- week-ahead (day+7): one mail per assigned person, all roles -----

    private int runWeekAheadPass(LocalDate dueDate) {
        Map<UUID, String> typeRoleLabels = roleLabelsByTypeId();
        int sent = 0;
        for (PlanningDay day : planningDays.findActiveByDate(dueDate)) {
            String locationName = locationName(day.getLocationId());
            for (PlanningDayAssignment a : day.getAssignments()) {
                Person person = persons.findActiveById(a.getAssignedPersonId()).orElse(null);
                if (person == null) {
                    continue;
                }
                String email = person.emailForCommunication();
                if (email == null || email.isBlank()) {
                    continue; // skip blank emails (legacy :136-137)
                }
                String role = typeRoleLabels.getOrDefault(a.getAssignmentTypeId(), "");
                PlanningDayAssignmentModel model = new PlanningDayAssignmentModel(
                        requireDate(day), locationName, day.getInfo(),
                        displayName(person), role, APP_LINK, mailSettings.from());
                mail.send(email, SUBJECT_ASSIGNMENT, TEMPLATE_ASSIGNMENT, model.asModel());
                sent++;
            }
        }
        return sent;
    }

    // ----- shared lookups -----

    /** This club's assignment-type-id → display role label (the German type name). */
    private Map<UUID, String> roleLabelsByTypeId() {
        Map<UUID, String> map = new HashMap<>();
        for (PlanningDayAssignmentType type : assignmentTypes.findActiveTypes()) {
            UUID id = type.getId();
            String name = type.getAssignmentTypeName();
            if (id != null && name != null) {
                map.put(id, name);
            }
        }
        return map;
    }

    private long reservationCount(PlanningDay day) {
        return planningDays.countReservationsForDay(requireDate(day), requireLocation(day));
    }

    private String locationName(@Nullable UUID locationId) {
        if (locationId == null) {
            return "";
        }
        return locations.findActiveById(locationId).map(Location::getLocationName).orElse("");
    }

    private String personName(UUID personId) {
        return persons.findActiveById(personId)
                .map(PlanningDayNotificationJob::displayName)
                .orElse("");
    }

    private static String displayName(Person person) {
        return (person.getFirstname() + " " + person.getLastname()).strip();
    }

    private static LocalDate requireDate(PlanningDay day) {
        return Optional.ofNullable(day.getPlanningDate())
                .orElseThrow(() -> new IllegalStateException("PlanningDay missing planningDate"));
    }

    private static UUID requireLocation(PlanningDay day) {
        return Optional.ofNullable(day.getLocationId())
                .orElseThrow(() -> new IllegalStateException("PlanningDay missing locationId"));
    }

    /**
     * Non-PII summary of one club's notification run — the audit {@code
     * after_state} (and the run-now endpoint's response). No recipient addresses
     * or names, just counts + the club id.
     */
    public record RunSummary(UUID clubId, int imminentMailCount, int weekAheadMailCount) {

        static RunSummary empty(UUID clubId) {
            return new RunSummary(clubId, 0, 0);
        }

        public int totalMailCount() {
            return imminentMailCount + weekAheadMailCount;
        }
    }
}

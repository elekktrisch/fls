package ch.alpenflight.planning.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.application.DeploymentContext;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.deployments.domain.Deployment;
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
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@MeasuredJob(name = PlanningDayNotificationJob.JOB_NAME,
        cron = "0 0 6 * * *",
        description = "Planning-day notifications (imminent ok/cancel + week-ahead assignments)")
public class PlanningDayNotificationJob implements BusinessJob {

    private static final Logger LOG = LoggerFactory.getLogger(PlanningDayNotificationJob.class);

    public static final String JOB_NAME = "planning-day-notification";

    static final String AUDIT_ENTITY_TYPE = "PlanningNotificationRun";

    static final int IMMINENT_OFFSET_DAYS = 1;

    static final int WEEK_AHEAD_OFFSET_DAYS = 7;

    static final String TEMPLATE_OK = "planningday-ok";
    static final String TEMPLATE_CANCEL = "planningday-cancel";
    static final String TEMPLATE_ASSIGNMENT = "planningday-assignment-notification";

    static final String SUBJECT_OK = "Flugbetriebstag findet statt";
    static final String SUBJECT_CANCEL = "Flugbetriebstag abgesagt";
    static final String SUBJECT_ASSIGNMENT = "Erinnerung: Einteilung Flugbetriebstag";

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
    private final DeploymentContext deploymentContext;
    private final ObjectProvider<PlanningDayNotificationJob> self;
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
                                      DeploymentContext deploymentContext,
                                      ObjectProvider<PlanningDayNotificationJob> self,
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
        this.deploymentContext = deploymentContext;
        this.self = self;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 6 * * *")
    @LifecycleStateFilter({LifecycleState.ACTIVE})
    public void runScheduled() {
        runForCurrentClub();
    }

    @Override
    public RunSummary runOnce() {
        int imminent = 0;
        int weekAhead = 0;
        for (Deployment deployment : deploymentContext.findDeployment(LifecycleState.ACTIVE)) {
            UUID deploymentId = deployment.getId();
            if (deploymentId == null) {
                continue;
            }
            RunSummary[] acc = {RunSummary.empty(ClubTenantIdentifierResolver.NO_TENANT)};
            deploymentContext.forEachClub(deploymentId, club ->
                    acc[0] = acc[0].plus(self.getObject().runForCurrentClub()));
            imminent += acc[0].imminentMailCount();
            weekAhead += acc[0].weekAheadMailCount();
        }
        return new RunSummary(ClubTenantIdentifierResolver.NO_TENANT, imminent, weekAhead);
    }

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
                    continue;
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

    public record RunSummary(UUID clubId, int imminentMailCount, int weekAheadMailCount) {

        static RunSummary empty(UUID clubId) {
            return new RunSummary(clubId, 0, 0);
        }

        RunSummary plus(RunSummary other) {
            return new RunSummary(clubId,
                    imminentMailCount + other.imminentMailCount,
                    weekAheadMailCount + other.weekAheadMailCount);
        }

        public int totalMailCount() {
            return imminentMailCount + weekAheadMailCount;
        }
    }
}

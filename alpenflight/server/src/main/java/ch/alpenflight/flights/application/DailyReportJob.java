package ch.alpenflight.flights.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.deployments.application.DeploymentContext;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightCrew;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightReportRow;
import ch.alpenflight.flights.domain.FlightReportRowRepository;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonClub;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily flight-report mail (S-084) — mirrors legacy {@code DailyReportJob.cs}:
 * per club, group the day's flights by the people who flew them (pilot, co-pilot,
 * instructor) and mail each opted-in person their own flights, then stamp the
 * flights so they are not reported twice.
 *
 * <p><strong>Opt-in.</strong> A person is mailed only when their membership in
 * <em>this</em> club carries {@code receiveFlightReports}
 * ({@code DailyReportJob.cs:79-88}); a blank communication address is skipped.
 *
 * <p><strong>Selection.</strong> Legacy takes flights "created today or modified
 * since the last report was sent". Nothing maintains {@code t_flight.modified_on}
 * here, so the modified-since half is not expressible; the pass takes flights
 * never reported ({@code flight_report_sent_on is null}) whose flying day falls in
 * the last {@value #REPORT_WINDOW_DAYS} days. The window is what keeps a flight
 * nobody opted in for from being rescanned forever — legacy got that for free from
 * its created-today predicate.
 */
@Component
@MeasuredJob(name = DailyReportJob.JOB_NAME,
        cron = DailyReportJob.CRON,
        description = "Daily flight report mail to pilots and instructors")
public class DailyReportJob implements BusinessJob {

    private static final Logger LOG = LoggerFactory.getLogger(DailyReportJob.class);

    /** Stable registry key — see {@link MeasuredJob#name()}. */
    public static final String JOB_NAME = "daily-report";

    static final String CRON = "0 0 21 * * *";

    static final String TEMPLATE = "flight-report";
    static final String SUBJECT = "Flugrapport";

    /** How many days back an unreported flight is still worth mailing. */
    static final int REPORT_WINDOW_DAYS = 2;

    /** Crew roles that receive a report of the flight ({@code :108-175}). */
    private static final Set<UUID> REPORTED_ROLES = Set.of(
            FlightCrewTypeIds.PILOT_OR_STUDENT,
            FlightCrewTypeIds.CO_PILOT,
            FlightCrewTypeIds.FLIGHT_INSTRUCTOR);

    private final FlightRepository flights;
    private final FlightReportRowRepository reportRows;
    private final PersonRepository persons;
    private final TemplatedMailService mail;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final DeploymentContext deploymentContext;
    private final ObjectProvider<DailyReportJob> self;
    private final Clock clock;

    public DailyReportJob(FlightRepository flights,
                          FlightReportRowRepository reportRows,
                          PersonRepository persons,
                          TemplatedMailService mail,
                          ClubTenantIdentifierResolver tenantResolver,
                          DeploymentContext deploymentContext,
                          ObjectProvider<DailyReportJob> self,
                          Clock clock) {
        this.flights = flights;
        this.reportRows = reportRows;
        this.persons = persons;
        this.mail = mail;
        this.tenantResolver = tenantResolver;
        this.deploymentContext = deploymentContext;
        this.self = self;
        this.clock = clock;
    }

    /** Scheduled tick — re-entered once per {@code ACTIVE} Club by the aspect. */
    @Scheduled(cron = CRON)
    @LifecycleStateFilter({LifecycleState.ACTIVE})
    public void runScheduled() {
        runForCurrentClub();
    }

    /** Cross-tenant "Run now" for the {@code /system/jobs} console. */
    @Override
    public RunSummary runOnce() {
        int mails = 0;
        for (Deployment deployment : deploymentContext.findDeployment(LifecycleState.ACTIVE)) {
            UUID deploymentId = deployment.getId();
            if (deploymentId == null) {
                continue;
            }
            int[] acc = {0};
            deploymentContext.forEachClub(deploymentId, club -> acc[0] += runFor(club));
            mails += acc[0];
        }
        return new RunSummary(mails);
    }

    private int runFor(Club club) {
        try {
            return self.getObject().runForCurrentClub().mailCount();
        } catch (RuntimeException e) {
            LOG.error("daily-report failed for club {} — continuing", club.getId(), e);
            return 0;
        }
    }

    /** Mails every opted-in person their unreported flights, for the current club. */
    @Transactional
    public RunSummary runForCurrentClub() {
        UUID clubId = tenantResolver.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(clubId)) {
            return new RunSummary(0);
        }
        Instant now = clock.instant();
        List<Flight> reportable = reportableFlights(now);
        if (reportable.isEmpty()) {
            return new RunSummary(0);
        }

        int mails = 0;
        for (Map.Entry<UUID, List<Flight>> entry : groupByCrewPerson(reportable).entrySet()) {
            if (mailTo(entry.getKey(), clubId, entry.getValue())) {
                entry.getValue().forEach(f -> {
                    f.markFlightReportSent(now);
                    flights.save(f);
                });
                mails++;
            }
        }
        return new RunSummary(mails);
    }

    private List<Flight> reportableFlights(Instant now) {
        LocalDate from = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(REPORT_WINDOW_DAYS);
        List<Flight> reportable = new ArrayList<>();
        for (Flight flight : flights.findUnreported()) {
            LocalDate date = flight.getFlightDate();
            if (date != null && !date.isBefore(from)) {
                reportable.add(flight);
            }
        }
        return reportable;
    }

    /** person id → the flights they were pilot, co-pilot, or instructor on. */
    private Map<UUID, List<Flight>> groupByCrewPerson(List<Flight> reportable) {
        Map<UUID, List<Flight>> byPerson = new LinkedHashMap<>();
        for (Flight flight : reportable) {
            for (UUID personId : reportedCrewOf(flight)) {
                byPerson.computeIfAbsent(personId, k -> new ArrayList<>()).add(flight);
            }
        }
        return byPerson;
    }

    private static Set<UUID> reportedCrewOf(Flight flight) {
        Set<UUID> people = new LinkedHashSet<>();
        for (FlightCrew crew : flight.getCrew()) {
            if (!crew.isDeleted() && REPORTED_ROLES.contains(crew.getFlightCrewTypeId())) {
                people.add(crew.getPersonId());
            }
        }
        return people;
    }

    /** @return true when a mail was actually sent to this person. */
    private boolean mailTo(UUID personId, UUID clubId, List<Flight> flown) {
        Person person = persons.findActiveById(personId).orElse(null);
        if (person == null || !receivesFlightReports(person, clubId)) {
            return false;
        }
        String email = person.emailForCommunication();
        if (email == null || email.isBlank()) {
            return false;
        }
        mail.send(email, SUBJECT, TEMPLATE,
                new FlightReportModel(displayName(person), linesFor(flown)).asModel());
        return true;
    }

    private static boolean receivesFlightReports(Person person, UUID clubId) {
        for (PersonClub membership : person.getActivePersonClubs()) {
            if (clubId.equals(membership.getClubId())) {
                return membership.isReceiveFlightReports();
            }
        }
        return false;
    }

    /**
     * One display line per flight, read from the decorated report read-model so
     * the mail carries the aircraft and locations without this job reaching into
     * the aircraft module.
     */
    private List<FlightLine> linesFor(List<Flight> flown) {
        List<FlightLine> lines = new ArrayList<>();
        for (Flight flight : flown) {
            UUID id = flight.getId();
            if (id == null) {
                continue;
            }
            FlightReportRow row = reportRows.findByFlightId(id).orElse(null);
            lines.add(new FlightLine(
                    flight.getFlightDate(),
                    row == null ? "" : nullToEmpty(row.getImmatriculation()),
                    row == null ? "" : nullToEmpty(row.getStartLocationName()),
                    row == null ? "" : nullToEmpty(row.getLdgLocationName()),
                    flight.getStartDateTime(),
                    flight.getLdgDateTime(),
                    row == null ? null : row.getDurationSeconds()));
        }
        return lines;
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String displayName(Person person) {
        return (person.getFirstname() + " " + person.getLastname()).strip();
    }

    /** One flight as the mail renders it. */
    public record FlightLine(@Nullable LocalDate date,
                             String immatriculation,
                             String startLocation,
                             String ldgLocation,
                             @Nullable Instant startTime,
                             @Nullable Instant ldgTime,
                             @Nullable Long durationSeconds) {

        /** Whole minutes aloft, for the mail's duration column. */
        public long durationMinutes() {
            return durationSeconds == null ? 0 : durationSeconds / 60;
        }
    }

    /** Thymeleaf binding for {@code flight-report.html}. */
    public record FlightReportModel(String personName, List<FlightLine> flights) {

        Map<String, Object> asModel() {
            return Map.of("report", this);
        }
    }

    /** Non-PII run summary: how many report mails the pass sent. */
    public record RunSummary(int mailCount) {

        @Override
        public String toString() {
            return mailCount + " report mails sent";
        }
    }
}

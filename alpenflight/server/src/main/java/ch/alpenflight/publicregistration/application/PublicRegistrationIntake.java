package ch.alpenflight.publicregistration.application;

import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.publicregistration.application.PublicClubResolver.PublicClub;
import ch.alpenflight.publicregistration.application.PublicRegistrationTxWriter.DiscoveryRegistration;
import ch.alpenflight.publicregistration.application.PublicRegistrationTxWriter.RegisteredPersons;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Accepts an anonymous flight-experience registration for the club its URL
 * names. The two-step shape is the S-025 mechanism and is load-bearing:
 *
 * <ol>
 *   <li>charge the attempt to its source address BEFORE anything else, so a
 *       probe that never resolves still costs the prober
 *       ({@link PublicRegistrationSubmitGuard});</li>
 *   <li>resolve + allowlist-check the slug OUTSIDE any tenant scope, so a
 *       rejected submission cannot touch tenant-scoped data;</li>
 *   <li>only then open a {@code Tenants.runAs} window for exactly the resolved
 *       club, with the transactional write nested inside it
 *       ({@link PublicRegistrationTxWriter}).</li>
 * </ol>
 *
 * <p>An HTTP interceptor establishing the tenant per request was rejected for
 * step 2: it would scope the failure paths too.
 *
 * <p>The notification mails go out inside that same window but outside the
 * writer's {@code @Transactional} boundary — after the rows are durable, and
 * still tenant-scoped so the club's {@code t_email_template} override resolves
 * ({@link PublicRegistrationMailer}).
 */
@Service
public class PublicRegistrationIntake {

    private final PublicRegistrationSubmitGuard guard;
    private final PublicClubResolver resolver;
    private final PublicRegistrationTxWriter writer;
    private final PublicRegistrationMailer mailer;
    private final DiscoveryFlightDayRepository discoveryDays;
    private final Clock clock;

    public PublicRegistrationIntake(PublicRegistrationSubmitGuard guard,
            PublicClubResolver resolver, PublicRegistrationTxWriter writer,
            PublicRegistrationMailer mailer, DiscoveryFlightDayRepository discoveryDays,
            Clock clock) {
        this.guard = guard;
        this.resolver = resolver;
        this.writer = writer;
        this.mailer = mailer;
        this.discoveryDays = discoveryDays;
        this.clock = clock;
    }

    /** Resolves the slug and records the accepted scenic-flight submission. */
    public PublicClub acceptScenic(String clubSlug, String clientIp) {
        return accept(clubSlug, clientIp, PublicRegistrationKind.SCENIC_FLIGHT);
    }

    /**
     * The days the club's picker may offer, ascending. A club that has published
     * none gets an empty list rather than an error — an empty picker is a valid
     * state of a club that has public registration open
     * ({@code RegistrationService.cs:39-52}). An unresolvable or closed club is
     * still 404 / 403, because that is a statement about the URL, not the days.
     *
     * <p>Unguarded on purpose: charging the read to the submit window would let
     * a visitor's own picker load spend the budget their submission then needs.
     */
    public List<LocalDate> bookableDiscoveryDays(String clubSlug) {
        PublicClub club = resolver.resolve(clubSlug);
        return Tenants.runAs(club.clubId(), () ->
                discoveryDays.findBookableFrom(LocalDate.now(clock)).stream()
                        .map(DiscoveryFlightDay::getEventDate)
                        .toList());
    }

    /**
     * Registers a discovery-flight candidate: a glider-trainee Person in the
     * resolved club, plus the club's double-seater booked all day on
     * {@code selectedDay} for them. A club that cannot be booked against still
     * gets the registration — see {@link DiscoveryReservationOutcome}.
     */
    public Accepted acceptDiscovery(String clubSlug, String clientIp,
            PublicRegistrantDetails registrant, @Nullable LocalDate selectedDay) {
        if (selectedDay == null) {
            throw new PublicRegistrationInvalidException("selectedDay is required");
        }
        guard.recordAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        DiscoveryRegistration written = Tenants.runAs(club.clubId(), () -> {
            requireBookableDay(selectedDay);
            DiscoveryRegistration registered = writer.registerDiscovery(club, registrant, selectedDay);
            mailer.sendDiscovery(club, registrant, selectedDay, registered.reservation());
            return registered;
        });
        return new Accepted(club, written.persons(), written.reservation());
    }

    /**
     * Without this the day picker is decorative: an anonymous caller could post
     * any date and have the club's glider blocked on it. Runs inside the tenant
     * window so the {@code @TenantId} discriminator is what rejects another
     * club's published day, and before the writer so a rejected date leaves no
     * Person, no membership and no reservation behind.
     */
    private void requireBookableDay(LocalDate selectedDay) {
        LocalDate today = LocalDate.now(clock);
        boolean bookable = discoveryDays.findActiveByEventDate(selectedDay)
                .filter(day -> day.isBookableOn(today))
                .isPresent();
        if (!bookable) {
            throw new PublicRegistrationInvalidException(
                    "selectedDay is not a bookable discovery-flight day of this club");
        }
    }

    /**
     * Registers a scenic-flight passenger: the same registrant write as
     * discovery with both trainee markers false, and no aircraft slot — the
     * scenic form carries no day to book one against.
     */
    public Accepted acceptScenic(String clubSlug, String clientIp,
            PublicRegistrantDetails registrant) {
        guard.recordAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        RegisteredPersons registered = Tenants.runAs(club.clubId(), () -> {
            RegisteredPersons persons = writer.registerScenic(club, registrant);
            mailer.sendScenic(club, registrant);
            return persons;
        });
        return new Accepted(club, registered, null);
    }

    private PublicClub accept(String clubSlug, String clientIp, PublicRegistrationKind kind) {
        guard.recordAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        Tenants.runAs(club.clubId(), () -> writer.recordAccepted(club, kind));
        return club;
    }

    /**
     * The resolved club, the Persons the submission created, and — discovery
     * only — what happened to the candidate's aircraft slot.
     */
    public record Accepted(PublicClub club, RegisteredPersons registered,
                           @Nullable DiscoveryReservationOutcome reservation) {}
}

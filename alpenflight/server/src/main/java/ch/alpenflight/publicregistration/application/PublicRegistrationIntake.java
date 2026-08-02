package ch.alpenflight.publicregistration.application;

import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.publicregistration.application.PublicClubResolver.PublicClub;
import ch.alpenflight.publicregistration.application.PublicRegistrationTxWriter.DiscoveryRegistration;
import ch.alpenflight.publicregistration.application.PublicRegistrationTxWriter.RegisteredPersons;
import java.time.LocalDate;
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
 */
@Service
public class PublicRegistrationIntake {

    private final PublicRegistrationSubmitGuard guard;
    private final PublicClubResolver resolver;
    private final PublicRegistrationTxWriter writer;

    public PublicRegistrationIntake(PublicRegistrationSubmitGuard guard,
            PublicClubResolver resolver, PublicRegistrationTxWriter writer) {
        this.guard = guard;
        this.resolver = resolver;
        this.writer = writer;
    }

    /** Resolves the slug and records the accepted discovery-flight submission. */
    public PublicClub acceptDiscovery(String clubSlug, String clientIp) {
        return accept(clubSlug, clientIp, PublicRegistrationKind.DISCOVERY_FLIGHT);
    }

    /** Resolves the slug and records the accepted scenic-flight submission. */
    public PublicClub acceptScenic(String clubSlug, String clientIp) {
        return accept(clubSlug, clientIp, PublicRegistrationKind.SCENIC_FLIGHT);
    }

    /**
     * Registers a discovery-flight candidate: a glider-trainee Person in the
     * resolved club, plus the club's double-seater booked all day on
     * {@code selectedDay} for them. A club that cannot be booked against still
     * gets the registration — see {@link DiscoveryReservationOutcome}.
     */
    public Accepted acceptDiscovery(String clubSlug, String clientIp,
            PublicRegistrantDetails registrant, LocalDate selectedDay) {
        if (selectedDay == null) {
            throw new PublicRegistrationInvalidException("selectedDay is required");
        }
        guard.recordAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        DiscoveryRegistration written = Tenants.runAs(club.clubId(),
                () -> writer.registerDiscovery(club, registrant, selectedDay));
        return new Accepted(club, written.persons(), written.reservation());
    }

    /** Registers a scenic-flight passenger: a Person in the resolved club, no trainee marker. */
    public Accepted acceptScenic(String clubSlug, String clientIp,
            PublicRegistrantDetails registrant) {
        return register(clubSlug, clientIp, PublicRegistrationKind.SCENIC_FLIGHT, registrant);
    }

    private PublicClub accept(String clubSlug, String clientIp, PublicRegistrationKind kind) {
        guard.recordAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        Tenants.runAs(club.clubId(), () -> writer.recordAccepted(club, kind));
        return club;
    }

    private Accepted register(String clubSlug, String clientIp,
            PublicRegistrationKind kind, PublicRegistrantDetails registrant) {
        guard.recordAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        RegisteredPersons registered =
                Tenants.runAs(club.clubId(), () -> writer.registerPersons(club, kind, registrant));
        return new Accepted(club, registered, null);
    }

    /**
     * The resolved club, the Persons the submission created, and — discovery
     * only — what happened to the candidate's aircraft slot.
     */
    public record Accepted(PublicClub club, RegisteredPersons registered,
                           @Nullable DiscoveryReservationOutcome reservation) {}
}

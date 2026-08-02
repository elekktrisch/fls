package ch.alpenflight.publicregistration.application;

import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.publicregistration.application.PublicClubResolver.PublicClub;
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

    private PublicClub accept(String clubSlug, String clientIp, PublicRegistrationKind kind) {
        guard.recordAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        Tenants.runAs(club.clubId(), () -> writer.recordAccepted(club, kind));
        return club;
    }
}

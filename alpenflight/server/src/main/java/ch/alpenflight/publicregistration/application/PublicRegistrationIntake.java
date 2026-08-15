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

@Service
public class PublicRegistrationIntake {

    private final PublicRegistrationAbuseGuard guard;
    private final PublicClubResolver resolver;
    private final PublicRegistrationTxWriter writer;
    private final PublicRegistrationMailer mailer;
    private final DiscoveryFlightDayRepository discoveryDays;
    private final Clock clock;

    public PublicRegistrationIntake(PublicRegistrationAbuseGuard guard,
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

    public PublicClub publicClub(String clubSlug, String clientIp) {
        guard.recordReadAndCheck(clientIp, clubSlug);
        return resolver.resolve(clubSlug);
    }

    public List<LocalDate> bookableDiscoveryDays(String clubSlug, String clientIp) {
        guard.recordReadAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        return Tenants.runAs(club.clubId(), () ->
                discoveryDays.findBookableFrom(LocalDate.now(clock)).stream()
                        .map(DiscoveryFlightDay::getEventDate)
                        .toList());
    }

    public Accepted acceptDiscovery(String clubSlug, String clientIp,
            PublicRegistrantDetails registrant, @Nullable LocalDate selectedDay) {
        if (selectedDay == null) {
            throw new PublicRegistrationInvalidException("selectedDay is required");
        }
        guard.recordSubmitAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        DiscoveryRegistration written = Tenants.runAs(club.clubId(), () -> {
            requireBookableDayPublishedByThisClub(selectedDay);
            DiscoveryRegistration registered = writer.registerDiscovery(club, registrant, selectedDay);
            mailer.sendDiscovery(club, registrant, selectedDay, registered.reservation());
            return registered;
        });
        return new Accepted(club, written.persons(), written.reservation());
    }

    private void requireBookableDayPublishedByThisClub(LocalDate selectedDay) {
        LocalDate today = LocalDate.now(clock);
        boolean bookable = discoveryDays.findActiveByEventDate(selectedDay)
                .filter(day -> day.isBookableOn(today))
                .isPresent();
        if (!bookable) {
            throw new PublicRegistrationInvalidException(
                    "selectedDay is not a bookable discovery-flight day of this club");
        }
    }

    public Accepted acceptScenic(String clubSlug, String clientIp,
            PublicRegistrantDetails registrant) {
        guard.recordSubmitAndCheck(clientIp, clubSlug);
        PublicClub club = resolver.resolve(clubSlug);
        RegisteredPersons registered = Tenants.runAs(club.clubId(), () -> {
            RegisteredPersons persons = writer.registerScenic(club, registrant);
            mailer.sendScenic(club, registrant);
            return persons;
        });
        return new Accepted(club, registered, null);
    }

    public record Accepted(PublicClub club, RegisteredPersons registered,
                           @Nullable DiscoveryReservationOutcome reservation) {}
}

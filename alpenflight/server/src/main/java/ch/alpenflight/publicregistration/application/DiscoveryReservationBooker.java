package ch.alpenflight.publicregistration.application;

import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.reservations.domain.AircraftReservationRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Blocks the club's double-seater glider for one discovery-flight candidate on
 * the day they picked.
 *
 * <h2>Why this does not go through {@code AircraftReservationsService}</h2>
 *
 * <p>Two different booking semantics share one aggregate. Member self-service
 * booking is <em>exclusive</em>: {@code AircraftReservationsService
 * .createReservation} runs a mandatory GiST exclusivity probe and answers 409,
 * because two members holding one aircraft at one time is a mistake. Organiser
 * block-booking is <em>not</em> exclusive: the club reserves the same glider all
 * day for every candidate who signs up for that discovery day, so five
 * candidates are five deliberately overlapping all-day reservations on one
 * airframe. Routed through the member-booking service, candidate #2 onward would
 * be rejected and a registration the club wants would fail — so the aggregate
 * factory and the repository are used directly here.
 *
 * <p>The bypass is contained by construction, not by convention: this class is
 * package-private inside {@code publicregistration}, a closed Spring Modulith
 * module with no named interface, so no member-facing package can name it, and
 * {@code ReservationExclusivityBypassAllowlistTest} fails the build if any class
 * outside the allow-list reaches the reservation factory or its repository.
 */
@Component
class DiscoveryReservationBooker {

    /**
     * Legacy matches {@code AircraftTypeId == AircraftType.Glider} — the pure
     * glider only, so a motorised glider is not eligible.
     */
    private static final String GLIDER_TYPE_CODE = "GLIDER";

    private static final int DOUBLE_SEATER_SEATS = 2;

    /** Legacy's literal reservation remark ({@code RegistrationService.cs:197}). */
    static final String CANDIDATE_REMARK = "Schnupperflug-Kandidat";

    private final ClubRepository clubs;
    private final AircraftRepository aircraft;
    private final AircraftReservationRepository reservations;

    DiscoveryReservationBooker(ClubRepository clubs,
                               AircraftRepository aircraft,
                               AircraftReservationRepository reservations) {
        this.clubs = clubs;
        this.aircraft = aircraft;
        this.reservations = reservations;
    }

    /**
     * Books the candidate's all-day slot, or reports why it was skipped. Never
     * throws for a missing prerequisite — the registration outranks the booking.
     */
    DiscoveryReservationOutcome book(UUID clubId, UUID candidatePersonId, LocalDate selectedDay) {
        Club club = clubs.findActiveById(clubId)
                .orElseThrow(() -> new IllegalStateException(
                        "Registration resolved a club that no longer exists: " + clubId));
        UUID homebaseId = club.getHomebaseId();
        UUID gliderId = findClubDoubleSeaterGlider(clubId);
        if (gliderId == null || homebaseId == null) {
            return DiscoveryReservationOutcome.skipped(gliderId == null, homebaseId == null);
        }

        Instant dayStart = selectedDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        AircraftReservation reservation = AircraftReservation.create(
                clubId,
                gliderId,
                candidatePersonId,
                homebaseId,
                null,
                // An unset or invalid discovery flight type must not cost the
                // club the booking: legacy swallows an undeserializable setting
                // value and books with none (RegistrationService.cs:201-212).
                club.getDiscoveryFlightTypeId(),
                dayStart,
                dayStart.plus(Duration.ofDays(1)),
                true,
                null,
                CANDIDATE_REMARK);
        AircraftReservation saved = reservations.save(reservation);
        return DiscoveryReservationOutcome.booked(
                Objects.requireNonNull(saved.getId(), "saved reservation has no id"));
    }

    private @Nullable UUID findClubDoubleSeaterGlider(UUID clubId) {
        return aircraft
                .findActiveOwnedIdsByTypeCodeAndSeats(clubId, GLIDER_TYPE_CODE, DOUBLE_SEATER_SEATS)
                .stream()
                .findFirst()
                .orElse(null);
    }
}

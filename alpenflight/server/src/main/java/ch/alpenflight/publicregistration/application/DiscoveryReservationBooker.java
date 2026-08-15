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

@Component
class DiscoveryReservationBooker {

    private static final String GLIDER_TYPE_CODE = "GLIDER";

    private static final int DOUBLE_SEATER_SEATS = 2;

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

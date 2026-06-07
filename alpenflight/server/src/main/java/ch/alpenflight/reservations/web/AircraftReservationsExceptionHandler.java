package ch.alpenflight.reservations.web;

import ch.alpenflight.platform.web.ProblemResponses;
import ch.alpenflight.reservations.domain.AircraftReservationNotFoundException;
import ch.alpenflight.reservations.domain.InvalidReservationDurationException;
import ch.alpenflight.reservations.domain.ReservationConflictException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@code AircraftReservation} domain exceptions to RFC 7807 problem
 * responses (J-5 T-05). Holds the only Spring-web coupling of the reservation
 * error vocabulary — the exception types stay in {@code reservations.domain}
 * free of {@code @ResponseStatus} per ADR 0023.
 *
 * <ul>
 *   <li>{@link ReservationConflictException} → <strong>409</strong>
 *       (key {@code aircraft.reservation.overlap}) — same-aircraft overlap.</li>
 *   <li>{@link InvalidReservationDurationException} → <strong>422</strong>
 *       (key {@code aircraft.reservation.duration}) — timed end ≤ start.</li>
 *   <li>{@link AircraftReservationNotFoundException} → 404.</li>
 *   <li>{@link IllegalArgumentException} → 400 (missing type reference,
 *       null factory args).</li>
 * </ul>
 */
@RestControllerAdvice(assignableTypes = AircraftReservationsController.class)
class AircraftReservationsExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:aircraft-reservation-not-found");
    private static final URI TYPE_OVERLAP =
            URI.create("urn:alpenflight:problem:aircraft-reservation-overlap");
    private static final URI TYPE_DURATION =
            URI.create("urn:alpenflight:problem:aircraft-reservation-duration");

    @ExceptionHandler(AircraftReservationNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(AircraftReservationNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Aircraft reservation not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(ReservationConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(ReservationConflictException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_OVERLAP);
        pd.setTitle("Reservation overlaps an existing booking");
        pd.setDetail(e.getMessage());
        pd.setProperty("key", "aircraft.reservation.overlap");
        return problem(pd);
    }

    @ExceptionHandler(InvalidReservationDurationException.class)
    ResponseEntity<ProblemDetail> handleDuration(InvalidReservationDurationException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setType(TYPE_DURATION);
        pd.setTitle("Invalid reservation duration");
        pd.setDetail(e.getMessage());
        pd.setProperty("key", "aircraft.reservation.duration");
        return problem(pd);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException e) {
        return ProblemResponses.badRequest(e);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ProblemResponses.problem(pd);
    }
}

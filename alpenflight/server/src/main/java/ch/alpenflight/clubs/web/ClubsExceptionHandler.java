package ch.alpenflight.clubs.web;

import ch.alpenflight.clubs.domain.ClubKeyAlreadyExistsException;
import ch.alpenflight.clubs.domain.ClubNotFoundException;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayAlreadyScheduledException;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayNotFoundException;
import ch.alpenflight.clubs.domain.InvalidClubReferenceException;
import ch.alpenflight.clubs.domain.SlugAlreadyExistsException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = ClubsController.class)
class ClubsExceptionHandler {

    public record ApiError(String field, String message) {}

    private static final URI TYPE_CLUB_KEY_CONFLICT =
            URI.create("urn:alpenflight:problem:club-key-conflict");

    private static final URI TYPE_INVALID_REFERENCE =
            URI.create("urn:alpenflight:problem:invalid-club-reference");

    private static final URI TYPE_DISCOVERY_DAY_CONFLICT =
            URI.create("urn:alpenflight:problem:discovery-flight-day-conflict");

    private static final URI TYPE_DISCOVERY_DAY_NOT_FOUND =
            URI.create("urn:alpenflight:problem:discovery-flight-day-not-found");

    private static final String DISCOVERY_DAY_UNIQUE_CONSTRAINT =
            "ux_discovery_flight_day_club_date";

    @ExceptionHandler(ClubNotFoundException.class)
    ResponseEntity<Void> handleNotFound(ClubNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(SlugAlreadyExistsException.class)
    ResponseEntity<Void> handleSlugConflict(SlugAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(ClubKeyAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleClubKeyConflict(ClubKeyAlreadyExistsException e) {
        return ProblemResponses.problem(clubKeyConflict(e.getMessage()));
    }

    @ExceptionHandler(DiscoveryFlightDayNotFoundException.class)
    ResponseEntity<ProblemDetail> handleDiscoveryDayNotFound(DiscoveryFlightDayNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_DISCOVERY_DAY_NOT_FOUND);
        pd.setTitle("Discovery-flight day not found");
        pd.setDetail(e.getMessage());
        return ProblemResponses.problem(pd);
    }

    @ExceptionHandler(DiscoveryFlightDayAlreadyScheduledException.class)
    ResponseEntity<ProblemDetail> handleDiscoveryDayConflict(
            DiscoveryFlightDayAlreadyScheduledException e) {
        return ProblemResponses.problem(discoveryDayConflict(e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("ux_club_key")) {
            return ProblemResponses.problem(clubKeyConflict("Club key already in use."));
        }
        if (message.contains("ux_club_slug")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        if (message.contains(DISCOVERY_DAY_UNIQUE_CONSTRAINT)) {
            return ProblemResponses.problem(
                    discoveryDayConflict("The club already offers a discovery-flight day on that date."));
        }
        if (message.contains("fk_club_discovery_flight_type_id")) {
            return ProblemResponses.problem(invalidReference(
                    "discoveryFlightTypeId", "No flight type with that id exists in this club."));
        }
        throw e;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException e) {
        return ProblemResponses.badRequest(e);
    }

    @ExceptionHandler(InvalidClubReferenceException.class)
    ResponseEntity<ApiError> handleInvalidReference(InvalidClubReferenceException e) {
        String message = e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(e.getField(), message == null ? "Invalid reference" : message));
    }

    private static ProblemDetail invalidReference(String field, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_INVALID_REFERENCE);
        pd.setTitle("Invalid club reference");
        pd.setDetail(detail);
        pd.setProperty("field", field);
        return pd;
    }

    private static ProblemDetail discoveryDayConflict(@Nullable String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_DISCOVERY_DAY_CONFLICT);
        pd.setTitle("Discovery-flight day already offered");
        pd.setDetail(detail);
        pd.setProperty("field", "eventDate");
        return pd;
    }

    private static ProblemDetail clubKeyConflict(@Nullable String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_CLUB_KEY_CONFLICT);
        pd.setTitle("Club key already in use");
        pd.setDetail(detail);
        pd.setProperty("field", "clubKey");
        return pd;
    }
}

package ch.alpenflight.flights.domain;

import ch.alpenflight.flights.domain.FlightValidator.ValidationError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates a flight and any flight it links to — the S-063 resolution of
 * the (a)/(b) fork: glider validity couples to tow validity via a single
 * sentinel error rather than inheriting the tow's nested error list.
 *
 * <p>Degenerates trivially for non-linking flight types: motor and tow rows
 * have no outbound link, so {@link #validate} returns their own per-flight
 * errors unchanged. The {@code Tow_*} in error codes reflects the only
 * inter-flight relationship in the model — not a restriction on which
 * flight types this validator accepts.
 *
 * <p>One-hop recursion only. Cycle safety is structural: {@link Flight#linkTow}
 * rejects non-GLIDER callers and non-TOW targets, so a TOW row cannot reach
 * the {@code start_type=Aerotow} branch where recursion lives. The
 * {@code towFlightId} field has no setter on {@link Flight}, closing the
 * raw-FK back-door.
 *
 * <p>The legacy validator at {@code FlightService.cs:987-1015} keeps the
 * pair independently valid. AlpenFlight diverges because the daily-validation
 * job and UI both prefer one verdict per pair.
 */
public final class FlightCompositeValidator {

    @FunctionalInterface
    public interface FlightLookup {
        Optional<Flight> findById(UUID id);
    }

    private FlightCompositeValidator() {}

    public static List<ValidationError> validate(Flight flight, FlightLookup lookup) {
        List<ValidationError> errors = new ArrayList<>(FlightValidator.validate(flight));
        if (flight.getFlightAircraftType() != FlightAircraftType.GLIDER) {
            return errors;
        }
        UUID towId = flight.getTowFlightId();
        if (towId == null) {
            return errors;
        }
        Optional<Flight> resolved = lookup.findById(towId);
        if (resolved.isEmpty() || resolved.get().isDeleted()) {
            errors.add(new ValidationError("VALIDATION_ERROR_Tow_flight_missing_or_deleted"));
            return errors;
        }
        if (!FlightValidator.validate(resolved.get()).isEmpty()) {
            errors.add(new ValidationError("VALIDATION_ERROR_Tow_flight_invalid"));
        }
        return errors;
    }
}

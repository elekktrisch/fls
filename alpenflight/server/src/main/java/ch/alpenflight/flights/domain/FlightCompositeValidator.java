package ch.alpenflight.flights.domain;

import ch.alpenflight.flights.domain.FlightValidator.ValidationError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

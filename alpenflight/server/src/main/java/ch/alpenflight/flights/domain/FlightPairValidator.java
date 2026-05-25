package ch.alpenflight.flights.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stub — implementation lands in the same PR as the red tests' green flip.
 *
 * <p>S-063 design choice (b) AC-as-written: pair validity. Glider validation
 * recurses one hop into the linked tow; if the tow has any per-flight error
 * a single sentinel {@code VALIDATION_ERROR_Tow_flight_invalid} is appended
 * to the glider's result. Dangling FKs (missing or tombstoned tow rows)
 * surface as {@code VALIDATION_ERROR_Tow_flight_missing_or_deleted}.
 */
public final class FlightPairValidator {

    @FunctionalInterface
    public interface FlightLookup {
        Optional<Flight> findById(UUID id);
    }

    private FlightPairValidator() {}

    public static List<FlightValidator.ValidationError> validate(Flight glider,
                                                                 FlightLookup lookup) {
        // Red stub — returns per-flight errors only. Real impl in next commit.
        return FlightValidator.validate(glider);
    }
}

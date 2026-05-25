package ch.alpenflight.flights.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Port of legacy {@code FlightService.cs:1073-1136} (ValidateFlightBasics)
 * + the start-type-specific arms at {@code :987-1039}.
 *
 * <p>Pure function: returns the list of error codes; mutating the flight's
 * {@code processState} based on the result is the caller's responsibility
 * (the daily validation job at S-083 or a future
 * {@code POST /flights/validate} endpoint). The aggregate's
 * {@link FlightProcessState} transitions still flow through the matrix
 * (S-059).
 *
 * <p>S-062a smoke depth only. Route-allow-list rules
 * ({@code FlightService.cs:1112-1136}) require resolved
 * {@code Location.InOutboundPoints} hydration and are deferred to S-101
 * along with multi-error-aggregation depth coverage.
 */
public final class FlightValidator {

    private static final UUID START_TYPE_AEROTOW =
            UUID.fromString("019e2e15-2c00-7fa1-8000-000000000fa1");
    private static final UUID START_TYPE_EXTERNAL =
            UUID.fromString("019e2e15-2c00-7fa3-8000-000000000fa3");
    private static final UUID START_TYPE_WINCH =
            UUID.fromString("019e2e15-2c00-7fa0-8000-000000000fa0");

    private FlightValidator() {}

    /** Single error code; payload kept ID-only so renderers can localise. */
    public record ValidationError(String code) {}

    public static List<ValidationError> validate(Flight flight) {
        if (flight == null) {
            throw new IllegalArgumentException("flight must not be null");
        }
        List<ValidationError> errors = new ArrayList<>();
        validateRequiredFields(flight, errors);
        validateStartTypeArms(flight, errors);
        return errors;
    }

    private static void validateRequiredFields(Flight f, List<ValidationError> errors) {
        if (f.getFlightDate() == null) {
            errors.add(new ValidationError("VALIDATION_ERROR_No_flight_date_set"));
        }
        if (!hasPilot(f)) {
            errors.add(new ValidationError("VALIDATION_ERROR_No_pilot_set"));
        }
        if (f.getStartDateTime() == null && !f.isNoStartTimeInformation()) {
            errors.add(new ValidationError("VALIDATION_ERROR_No_start_time_information_set"));
        }
        if (f.getLdgDateTime() == null && !f.isNoLdgTimeInformation()) {
            errors.add(new ValidationError("VALIDATION_ERROR_No_landing_time_information_set"));
        }
        if (f.getStartLocationId() == null) {
            errors.add(new ValidationError("VALIDATION_ERROR_No_start_location_set"));
        }
        if (f.getLdgLocationId() == null) {
            errors.add(new ValidationError("VALIDATION_ERROR_No_landing_location_set"));
        }
        if (f.getStartTypeId() == null) {
            errors.add(new ValidationError("VALIDATION_ERROR_No_start_type_set"));
        }
        if (f.getFlightTypeId() == null) {
            errors.add(new ValidationError("VALIDATION_ERROR_No_flight_type_set"));
        }
        if (f.getLdgDateTime() != null && !f.isNoLdgTimeInformation()) {
            Short n = f.getNrOfLdgs();
            if (n == null) {
                errors.add(new ValidationError("VALIDATION_ERROR_Number_of_landings_not_set"));
            } else if (n < 1) {
                errors.add(new ValidationError("VALIDATION_ERROR_Number_of_landings_is_less_then_1"));
            }
        }
    }

    private static void validateStartTypeArms(Flight f, List<ValidationError> errors) {
        UUID startType = f.getStartTypeId();
        if (startType == null) {
            return;
        }
        if (START_TYPE_AEROTOW.equals(startType)) {
            if (f.getFlightAircraftType() == FlightAircraftType.GLIDER
                    && f.getTowFlightId() == null) {
                errors.add(new ValidationError(
                        "VALIDATION_ERROR_No_towing_flight_referenced_for_towed_glider_flight"));
            }
        } else if (START_TYPE_EXTERNAL.equals(startType)) {
            if (f.getTowFlightId() != null) {
                errors.add(new ValidationError(
                        "VALIDATION_ERROR_Towing_flight_referenced_for_externally_started_glider_flight"));
            }
        } else if (START_TYPE_WINCH.equals(startType)) {
            if (!hasCrewOfType(f, FlightCrewTypeIds.WINCH_OPERATOR)) {
                errors.add(new ValidationError(
                        "VALIDATION_ERROR_No_winch_operator_set_for_winch_started_glider_flight"));
            }
        }
    }

    private static boolean hasPilot(Flight f) {
        return hasCrewOfType(f, FlightCrewTypeIds.PILOT_OR_STUDENT);
    }

    private static boolean hasCrewOfType(Flight f, UUID crewTypeId) {
        for (FlightCrew c : f.getCrew()) {
            if (crewTypeId.equals(c.getFlightCrewTypeId()) && !c.isDeleted()) {
                return true;
            }
        }
        return false;
    }
}

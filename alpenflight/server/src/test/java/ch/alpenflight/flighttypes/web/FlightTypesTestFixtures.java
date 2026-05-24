package ch.alpenflight.flighttypes.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-process unique-name helpers + canonical payload shapes for the
 * FlightType ITs. Name uniqueness is per-tenant (V11 partial UNIQUE), so the
 * suffix only needs to dodge per-process collisions across @Test methods —
 * the @BeforeEach tenant pre-clean does the cross-class cleanup.
 */
final class FlightTypesTestFixtures {

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    private FlightTypesTestFixtures() {}

    static String uniqueName() {
        return "IT_FT_" + NAME_COUNTER.incrementAndGet();
    }

    /** Glider-only FlightType payload — instructor-required, 2 seats min. */
    static Map<String, Object> createPayload(String name) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("flightTypeName", name);
        n.put("flightCode", null);
        n.put("isInstructorRequired", true);
        n.put("isObserverPilotOrInstructorRequired", false);
        n.put("isCheckFlight", false);
        n.put("isPassengerFlight", false);
        n.put("isSoloFlight", false);
        n.put("isForGliderFlights", true);
        n.put("isForTowFlights", false);
        n.put("isForMotorFlights", false);
        n.put("isFlightCostBalanceSelectable", false);
        n.put("isCouponNumberRequired", false);
        n.put("isForAircraftReservationType", false);
        n.put("minNrOfAircraftSeatsRequired", 2);
        return n;
    }

    static Map<String, Object> updatePayload(String name) {
        Map<String, Object> n = createPayload(name);
        n.put("isInstructorRequired", false);
        n.put("isPassengerFlight", true);
        return n;
    }
}

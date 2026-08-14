package ch.alpenflight.flighttypes.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class FlightTypesTestFixtures {

    private static final AtomicInteger NAME_COUNTER = new AtomicInteger(0);

    private FlightTypesTestFixtures() {}

    static String uniqueName() {
        return "IT_FT_" + NAME_COUNTER.incrementAndGet();
    }

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

package ch.alpenflight.aircraft.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Canonical seed UUIDs the Aircraft ITs round-trip through DTOs, plus
 * unique-immatriculation helpers for per-test isolation.
 */
final class AircraftsTestFixtures {

    static final String SEED_AIRCRAFT_TYPE_GLIDER = "019e2e15-2c00-7af9-8000-000000002af9";
    static final String SEED_AIRCRAFT_TYPE_GLIDER_WITH_MOTOR =
            "019e2e15-2c00-7afa-8000-000000002afa";
    static final String SEED_AIRCRAFT_TYPE_MOTOR_AIRCRAFT = "019e2e15-2c00-7afc-8000-000000002afc";
    static final String SEED_AIRCRAFT_STATE_OK = "019e2e15-2c00-7ee0-8000-000000002ee0";
    static final String SEED_AIRCRAFT_STATE_MAINTENANCE = "019e2e15-2c00-7ee4-8000-000000002ee4";

    private static final AtomicInteger IMMAT_COUNTER = new AtomicInteger(0);

    private AircraftsTestFixtures() {}

    /**
     * Unique immatriculation in {@code HB-A###} form (HB country prefix + per-process
     * counter), well below the 15-char cap.
     */
    static String uniqueImmatriculation() {
        int n = IMMAT_COUNTER.incrementAndGet();
        char letter = (char) ('A' + ((n / 1000) % 26));
        return "HB-" + letter + String.format("%03d", n % 1000);
    }

    /**
     * Builds an {@code AircraftCreateRequest} payload as JSON-shaped map.
     * Tenant scoping (S-159): {@code managing_club_id} is set by Hibernate
     * from the JWT resolver — neither callers nor the DTO carry it.
     * Ownership defaults to the managing club at the service layer; change
     * via the transfer-ownership endpoint.
     */
    static Map<String, Object> createPayload(String immatriculation) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("aircraftTypeId", SEED_AIRCRAFT_TYPE_GLIDER);
        n.put("immatriculation", immatriculation);
        n.put("manufacturerName", "Schleicher");
        n.put("aircraftModel", "ASK-21");
        n.put("nrOfSeats", 2);
        n.put("isTowingOrWinchRequired", true);
        n.put("isTowingStartAllowed", true);
        n.put("isWinchStartAllowed", true);
        n.put("isTowingAircraft", false);
        return n;
    }

    static Map<String, Object> updatePayload(String immatriculation) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("aircraftTypeId", SEED_AIRCRAFT_TYPE_GLIDER);
        n.put("immatriculation", immatriculation);
        n.put("manufacturerName", "Schleicher");
        n.put("aircraftModel", "ASK-21B");
        n.put("nrOfSeats", 2);
        n.put("isTowingOrWinchRequired", true);
        n.put("isTowingStartAllowed", true);
        n.put("isWinchStartAllowed", true);
        n.put("isTowingAircraft", false);
        n.put("comment", "edited by IT");
        return n;
    }
}

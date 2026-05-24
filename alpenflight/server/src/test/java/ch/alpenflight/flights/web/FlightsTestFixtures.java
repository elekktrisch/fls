package ch.alpenflight.flights.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared seed helpers + canonical reference UUIDs for the Flight ITs. The
 * Flights ITs need a tenant-scoped Aircraft row to anchor the
 * {@code aircraft_id} FK; the seed migration only ships reference data,
 * so each IT seeds its own minimal Aircraft up-front.
 */
final class FlightsTestFixtures {

    static final String SEED_AIRCRAFT_TYPE_GLIDER =
            "019e2e15-2c00-7af9-8000-000000002af9";
    static final String SEED_FLIGHT_CREW_TYPE_PIC =
            "019e2e15-2c00-76b0-8000-0000000036b0";

    private static final AtomicInteger IMMAT_COUNTER = new AtomicInteger(0);
    private static final AtomicInteger AIRCRAFT_COUNTER = new AtomicInteger(0);

    private FlightsTestFixtures() {}

    static String uniqueImmatriculation() {
        int n = IMMAT_COUNTER.incrementAndGet();
        char letter = (char) ('A' + ((n / 1000) % 26));
        return "HB-FT" + letter + String.format("%02d", n % 100);
    }

    /**
     * Inserts a minimal Aircraft row under the given managing tenant and
     * returns the new id. Used by ITs that need a writable
     * {@code aircraft_id} FK for Flight rows. Reference data
     * ({@code aircraft_type}) is V3-seeded; clubs are V5-seeded or seeded
     * by the calling fixture.
     */
    static UUID seedAircraftFor(JdbcTemplate jdbc, UUID managingClubId) {
        UUID id = newAircraftId();
        jdbc.update("""
                INSERT INTO aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                                      immatriculation, is_towing_or_winch_required,
                                      is_towing_start_allowed, is_winch_start_allowed,
                                      is_towing_aircraft, is_fast_entry_record,
                                      nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid,
                        ?, false, false, false, false, false, 2)
                """,
                id.toString(),
                managingClubId.toString(),
                managingClubId.toString(),
                SEED_AIRCRAFT_TYPE_GLIDER,
                uniqueImmatriculation());
        return id;
    }

    /**
     * Inserts a minimal Person row + per-tenant PersonClub row, returns the
     * Person id. Used by ITs exercising FlightCrew.
     */
    static UUID seedPersonInClub(JdbcTemplate jdbc, UUID clubId) {
        UUID personId = UUID.randomUUID();
        UUID pcId = UUID.randomUUID();
        jdbc.update("INSERT INTO person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "Test", "Pilot" + IMMAT_COUNTER.incrementAndGet());
        jdbc.update("""
                INSERT INTO person_club (id, person_id, club_id)
                VALUES (?::uuid, ?::uuid, ?::uuid)
                """,
                pcId.toString(), personId.toString(), clubId.toString());
        return personId;
    }

    /** Inserts a minimal Person row WITHOUT any PersonClub — cross-tenant ride-through anchor. */
    static UUID seedPersonNoMembership(JdbcTemplate jdbc) {
        UUID personId = UUID.randomUUID();
        jdbc.update("INSERT INTO person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "Cross", "Tenant" + IMMAT_COUNTER.incrementAndGet());
        return personId;
    }

    /**
     * Minimal create payload — only the required fields (aircraftType +
     * aircraftId) and a sensible default flightDate. Caller can extend
     * before posting.
     */
    static Map<String, Object> createPayload(String flightAircraftType,
                                             String aircraftIdExternal,
                                             @Nullable String flightDateIso) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightAircraftType", flightAircraftType);
        body.put("aircraftId", aircraftIdExternal);
        if (flightDateIso != null) {
            body.put("flightDate", flightDateIso);
        }
        // Primitive booleans on the DTO — Jackson 3 rejects null for these,
        // so fill explicitly with false defaults.
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
        body.put("crew", new ArrayList<>());
        return body;
    }

    static Map<String, Object> crewItem(String personIdExternal, String crewTypeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("personId", personIdExternal);
        m.put("flightCrewTypeId", crewTypeId);
        return m;
    }

    static List<Map<String, Object>> singletonCrew(Map<String, Object> item) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(item);
        return out;
    }

    /**
     * Cleans Flight + FlightCrew rows under the given clubs. Pre-clean
     * convention per ADR 0021 — call from {@code @BeforeEach}.
     */
    static void cleanFlightRowsFor(JdbcTemplate jdbc, UUID... clubIds) {
        for (UUID clubId : clubIds) {
            // FK ON DELETE CASCADE on flight_crew → flight handles crew cleanup.
            jdbc.update("DELETE FROM flight WHERE operating_club_id = ?::uuid",
                    clubId.toString());
        }
    }

    private static UUID newAircraftId() {
        int n = AIRCRAFT_COUNTER.incrementAndGet();
        // Stable per-process unique slot under the 019e2e15-* test-fixture prefix.
        String suffix = String.format("%012x", 0xff_0000_0000L + n);
        return UUID.fromString("019e2e15-2c00-7e15-8000-" + suffix);
    }
}

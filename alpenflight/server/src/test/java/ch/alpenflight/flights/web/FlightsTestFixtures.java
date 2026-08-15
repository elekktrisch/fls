package ch.alpenflight.flights.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

final class FlightsTestFixtures {

    static final String SEED_AIRCRAFT_TYPE_GLIDER =
            "019e2e15-2c00-7af9-8000-000000002af9";
    static final String SEED_FLIGHT_CREW_TYPE_PIC =
            "019e2e15-2c00-76b0-8000-0000000036b0";

    private static final AtomicInteger IMMAT_COUNTER = new AtomicInteger(0);
    private static final AtomicInteger AIRCRAFT_COUNTER = new AtomicInteger(0);

    private static final String TEST_FIXTURE_AIRCRAFT_ID_PREFIX = "019e2e15-2c00-7e15-8000-";
    private static final long TEST_FIXTURE_ID_SLOT_BASE = 0xff_0000_0000L;

    private FlightsTestFixtures() {}

    static String uniqueImmatriculation() {
        int n = IMMAT_COUNTER.incrementAndGet();
        char letter = (char) ('A' + ((n / 1000) % 26));
        return "HB-FT" + letter + String.format("%02d", n % 100);
    }

    static UUID seedAircraftFor(JdbcTemplate jdbc, UUID managingClubId) {
        UUID id = newAircraftId();
        jdbc.update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
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

    static UUID seedPersonInClub(JdbcTemplate jdbc, UUID clubId) {
        UUID personId = UUID.randomUUID();
        UUID pcId = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "Test", "Pilot" + IMMAT_COUNTER.incrementAndGet());
        jdbc.update("""
                INSERT INTO t_person_club (id, person_id, club_id)
                VALUES (?::uuid, ?::uuid, ?::uuid)
                """,
                pcId.toString(), personId.toString(), clubId.toString());
        return personId;
    }

    static UUID seedPersonNoMembership(JdbcTemplate jdbc) {
        UUID personId = UUID.randomUUID();
        jdbc.update("INSERT INTO t_person (id, firstname, lastname) VALUES (?::uuid, ?, ?)",
                personId.toString(), "Cross", "Tenant" + IMMAT_COUNTER.incrementAndGet());
        return personId;
    }

    static Map<String, Object> createPayload(String flightAircraftType,
                                             String aircraftIdExternal,
                                             @Nullable String flightDateIso) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flightAircraftType", flightAircraftType);
        body.put("aircraftId", aircraftIdExternal);
        if (flightDateIso != null) {
            body.put("flightDate", flightDateIso);
        }
        putBooleanFlagsThatJacksonRejectsAsNullBecauseTheDtoFieldsArePrimitive(body);
        body.put("crew", new ArrayList<>());
        return body;
    }

    private static void putBooleanFlagsThatJacksonRejectsAsNullBecauseTheDtoFieldsArePrimitive(
            Map<String, Object> body) {
        body.put("isSoloFlight", false);
        body.put("noStartTimeInformation", false);
        body.put("noLdgTimeInformation", false);
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

    static void cleanFlightRowsFor(JdbcTemplate jdbc, UUID... clubIds) {
        for (UUID clubId : clubIds) {
            jdbc.update("DELETE FROM t_flight WHERE operating_club_id = ?::uuid",
                    clubId.toString());
        }
    }

    private static UUID newAircraftId() {
        int n = AIRCRAFT_COUNTER.incrementAndGet();
        String perProcessUniqueSlot = String.format("%012x", TEST_FIXTURE_ID_SLOT_BASE + n);
        return UUID.fromString(TEST_FIXTURE_AIRCRAFT_ID_PREFIX + perProcessUniqueSlot);
    }
}

package ch.alpenflight.flights.domain;

import java.util.UUID;

public enum FlightProcessState {

    NOT_PROCESSED("019e2e15-2c00-7a98-8000-000000003a98", 0),
    INVALID("019e2e15-2c00-7a99-8000-000000003a99", 28),
    VALID("019e2e15-2c00-7a9a-8000-000000003a9a", 30),
    LOCKED("019e2e15-2c00-7a9b-8000-000000003a9b", 40),
    DELIVERY_PREPARATION_ERROR("019e2e15-2c00-7a9c-8000-000000003a9c", 45),
    DELIVERY_PREPARED("019e2e15-2c00-7a9d-8000-000000003a9d", 50),
    DELIVERY_BOOKED("019e2e15-2c00-7a9e-8000-000000003a9e", 60),
    EXCLUDED_FROM_DELIVERY_PROCESS("019e2e15-2c00-7a9f-8000-000000003a9f", 99);

    private final UUID id;
    private final short legacyCode;

    FlightProcessState(String id, int legacyCode) {
        this.id = UUID.fromString(id);
        this.legacyCode = (short) legacyCode;
    }

    public UUID id() {
        return id;
    }

    public short legacyCode() {
        return legacyCode;
    }

    public static FlightProcessState fromId(UUID id) {
        for (FlightProcessState s : values()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown flight_process_state.id: " + id);
    }
}

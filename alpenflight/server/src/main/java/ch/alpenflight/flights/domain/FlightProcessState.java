package ch.alpenflight.flights.domain;

import java.util.UUID;

/**
 * Flight process-state values. The aggregate persists a UUID FK to the
 * {@code t_flight_process_state} reference table; this enum carries the
 * canonical UUID + legacy SMALLINT code per row so the matrix, audit
 * payload, and API can all key off the enum constant.
 *
 * <p>The UUIDs are identity-bearing per ADR 0019 — generated once by
 * {@code GenerateCanonicalUuids} and pinned in V3 + the canonical-uuids
 * JSON ground truth. {@link FlightProcessStateSeedVerifier} asserts at
 * startup that the seeded rows match these constants, so a future
 * migration that drifts the UUIDs fails fast.
 *
 * <p>The {@code legacyCode} smallints mirror the legacy
 * {@code FLS.Data.WebApi.Flight.FlightProcessState} enum (0/28/30/40/45/
 * 50/60/99) and are stable input to e2e parity tests.
 */
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

    /** Resolve an enum from a seeded {@code flight_process_state.id}. */
    public static FlightProcessState fromId(UUID id) {
        for (FlightProcessState s : values()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown flight_process_state.id: " + id);
    }
}

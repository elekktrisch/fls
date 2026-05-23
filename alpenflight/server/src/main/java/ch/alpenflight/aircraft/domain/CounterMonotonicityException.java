package ch.alpenflight.aircraft.domain;

/**
 * Raised when a new {@code aircraft_operating_counter} row would either
 * decrease a previously-recorded total (airframe-lifetime totals are
 * monotonic non-decreasing) or share an {@code at_date_time} with an
 * existing row for the same aircraft (V3 partial unique
 * {@code ux_aoc_aircraft_at_date_time}). Translated to HTTP 409.
 */
public class CounterMonotonicityException extends RuntimeException {

    public CounterMonotonicityException(String message) {
        super(message);
    }

    public CounterMonotonicityException(String message, Throwable cause) {
        super(message, cause);
    }
}

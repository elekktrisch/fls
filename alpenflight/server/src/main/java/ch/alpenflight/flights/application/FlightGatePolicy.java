package ch.alpenflight.flights.application;

import ch.alpenflight.flights.domain.Flight;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * The flight time-gate (S-061). Decides when a flight may lock and when it
 * may be billed (delivery-prepared).
 *
 * <p><b>Deliberate divergence from legacy</b> (J-2 parity decision, operator
 * 2026-06-03 — see {@code docs/modernization/stories/J-2-flight-list-edit.md}
 * "Parity decisions"). Legacy keys <em>both</em> gates on {@code CreatedOn}
 * (the record-entry date, {@code FlightService.cs:1157,1164} +
 * {@code DeliveryService.cs:65,97}) and has no {@code locked_at} column.
 * The operator chose the S-061 wording instead:
 *
 * <ul>
 *   <li>{@link #canLock} — lock 2 days after the flight <em>flew</em>:
 *       {@code flight_date <= today - 2 days}.</li>
 *   <li>{@link #canBill} — bill 3 days after the flight <em>locked</em>:
 *       {@code locked_at <= today - 3 days}.</li>
 * </ul>
 *
 * <p>Comparison is by calendar day (both sides truncated to a
 * {@link LocalDate}) in the server's configured zone (UTC, per
 * {@code application.yml} + {@code TimeConfig}). "Now" is passed in by the
 * caller — which derives it from the injected {@link java.time.Clock} — so
 * tests pin the boundary with {@link java.time.Clock#fixed}.
 *
 * <p>Per ADR 0022 directive 2 this rule lives in code, not the schema.
 */
@Component
public class FlightGatePolicy {

    /** Server zone for the calendar-day truncation. */
    private static final ZoneOffset ZONE = ZoneOffset.UTC;

    private static final long LOCK_AFTER_DAYS = 2;
    private static final long BILL_AFTER_DAYS = 3;

    /**
     * True when the flight may transition Valid → Locked: its flying day is
     * at least {@value #LOCK_AFTER_DAYS} calendar days in the past relative
     * to {@code now}. A flight with no {@code flight_date} cannot lock.
     */
    public boolean canLock(Flight flight, Instant now) {
        LocalDate flightDate = flight.getFlightDate();
        if (flightDate == null) {
            return false;
        }
        LocalDate gate = today(now).minusDays(LOCK_AFTER_DAYS);
        return !flightDate.isAfter(gate);
    }

    /**
     * True when the flight may transition Locked → DeliveryPrepared (be
     * billed): its lock day is at least {@value #BILL_AFTER_DAYS} calendar
     * days in the past relative to {@code now}. A flight that has never
     * been locked (no {@code locked_at}) cannot be billed.
     */
    public boolean canBill(Flight flight, Instant now) {
        Instant lockedAt = flight.getLockedAt();
        if (lockedAt == null) {
            return false;
        }
        LocalDate lockedDay = LocalDate.ofInstant(lockedAt, ZONE);
        LocalDate gate = today(now).minusDays(BILL_AFTER_DAYS);
        return !lockedDay.isAfter(gate);
    }

    private static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZONE);
    }
}

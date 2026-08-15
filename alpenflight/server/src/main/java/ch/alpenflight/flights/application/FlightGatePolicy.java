package ch.alpenflight.flights.application;

import ch.alpenflight.flights.domain.Flight;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class FlightGatePolicy {

    private static final ZoneOffset CALENDAR_DAY_ZONE = ZoneOffset.UTC;

    private static final long LOCK_AFTER_DAYS = 2;
    private static final long BILL_AFTER_DAYS = 3;

    public boolean canLock(Flight flight, Instant now) {
        LocalDate flightDate = flight.getFlightDate();
        if (flightDate == null) {
            return false;
        }
        LocalDate gate = today(now).minusDays(LOCK_AFTER_DAYS);
        return !flightDate.isAfter(gate);
    }

    public boolean canBill(Flight flight, Instant now) {
        Instant lockedAt = flight.getLockedAt();
        if (lockedAt == null) {
            return false;
        }
        LocalDate lockedDay = LocalDate.ofInstant(lockedAt, CALENDAR_DAY_ZONE);
        LocalDate gate = today(now).minusDays(BILL_AFTER_DAYS);
        return !lockedDay.isAfter(gate);
    }

    private static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, CALENDAR_DAY_ZONE);
    }
}

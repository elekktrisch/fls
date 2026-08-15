package ch.alpenflight.flights.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record FlightListCursor(@Nullable LocalDate flightDate, UUID id) {

    private static final String NULL_DATE = "null";

    public String encode() {
        String raw = (flightDate == null ? NULL_DATE : flightDate.toString())
                + "|" + id.toString();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static FlightListCursor decode(String wire) {
        if (wire == null || wire.isBlank()) {
            throw new InvalidCursorException("cursor must not be blank");
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(wire), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("cursor is not valid base64url", e);
        }
        int sep = raw.indexOf('|');
        if (sep < 0) {
            throw new InvalidCursorException("cursor missing separator");
        }
        String dateLit = raw.substring(0, sep);
        String idLit = raw.substring(sep + 1);
        LocalDate flightDate;
        try {
            flightDate = NULL_DATE.equals(dateLit) ? null : LocalDate.parse(dateLit);
        } catch (DateTimeParseException e) {
            throw new InvalidCursorException("cursor flightDate is not a valid ISO date", e);
        }
        UUID id;
        try {
            id = UUID.fromString(idLit);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("cursor id is not a valid UUID", e);
        }
        return new FlightListCursor(flightDate, id);
    }
}

package ch.alpenflight.flights.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Opaque keyset-cursor for {@code GET /api/v1/flights}. Encodes
 * {@code (flightDate, id)} of the last-returned row as URL-safe base64;
 * the next page request returns rows strictly less than this pair under
 * {@code ORDER BY flight_date DESC, id DESC}.
 *
 * <p>Wire form: {@code base64url(flightDate.toString() + "|" + id.toString())}.
 * {@code flightDate} may be {@code null} (Flight allows NULL flight_date);
 * encoded as {@code "null"} sentinel — pages past the NULL block reset to
 * no cursor on the FE side.
 */
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
            throw new IllegalArgumentException("cursor must not be blank");
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(wire), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cursor is not valid base64url", e);
        }
        int sep = raw.indexOf('|');
        if (sep < 0) {
            throw new IllegalArgumentException("cursor missing separator");
        }
        String dateLit = raw.substring(0, sep);
        String idLit = raw.substring(sep + 1);
        LocalDate flightDate = NULL_DATE.equals(dateLit) ? null : LocalDate.parse(dateLit);
        UUID id;
        try {
            id = UUID.fromString(idLit);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cursor id is not a valid UUID", e);
        }
        return new FlightListCursor(flightDate, id);
    }
}

package ch.alpenflight.locations.domain;

public class IcaoCodeInvalidException extends RuntimeException {

    public IcaoCodeInvalidException(String icaoCode) {
        super("ICAO code must match ^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$, got: " + icaoCode);
    }
}

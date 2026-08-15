package ch.alpenflight.locations.domain;

public class IcaoCodeAlreadyExistsException extends RuntimeException {

    public IcaoCodeAlreadyExistsException(String icaoCode) {
        super("Location ICAO code already in use within this club: " + icaoCode);
    }
}

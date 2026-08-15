package ch.alpenflight.aircraft.domain;

public class DuplicateImmatriculationException extends RuntimeException {

    public DuplicateImmatriculationException(String immatriculation) {
        super("Aircraft immatriculation already in use: " + immatriculation);
    }
}

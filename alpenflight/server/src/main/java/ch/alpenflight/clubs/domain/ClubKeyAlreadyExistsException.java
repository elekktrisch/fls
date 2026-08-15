package ch.alpenflight.clubs.domain;

public class ClubKeyAlreadyExistsException extends RuntimeException {

    public ClubKeyAlreadyExistsException(String clubKey) {
        super("Club key already in use: " + clubKey);
    }
}

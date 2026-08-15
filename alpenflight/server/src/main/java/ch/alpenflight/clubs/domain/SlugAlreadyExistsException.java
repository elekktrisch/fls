package ch.alpenflight.clubs.domain;

public class SlugAlreadyExistsException extends RuntimeException {

    public SlugAlreadyExistsException(String slug) {
        super("Club slug already in use: " + slug);
    }
}

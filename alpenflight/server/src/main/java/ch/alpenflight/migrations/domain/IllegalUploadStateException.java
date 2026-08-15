package ch.alpenflight.migrations.domain;

public class IllegalUploadStateException extends RuntimeException {

    public IllegalUploadStateException(String message) {
        super(message);
    }
}

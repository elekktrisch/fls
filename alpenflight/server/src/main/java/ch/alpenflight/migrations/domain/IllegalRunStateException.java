package ch.alpenflight.migrations.domain;

public class IllegalRunStateException extends RuntimeException {

    public IllegalRunStateException(String message) {
        super(message);
    }
}

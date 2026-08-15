package ch.alpenflight.aircraft.domain;

public class CounterMonotonicityException extends RuntimeException {

    public CounterMonotonicityException(String message) {
        super(message);
    }

    public CounterMonotonicityException(String message, Throwable cause) {
        super(message, cause);
    }
}

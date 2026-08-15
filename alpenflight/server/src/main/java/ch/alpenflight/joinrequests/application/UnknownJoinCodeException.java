package ch.alpenflight.joinrequests.application;

public class UnknownJoinCodeException extends RuntimeException {

    public UnknownJoinCodeException() {
        super("No active club resolves to that join code");
    }
}

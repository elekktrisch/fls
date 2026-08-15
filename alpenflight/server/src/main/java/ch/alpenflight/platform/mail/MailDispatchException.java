package ch.alpenflight.platform.mail;

public class MailDispatchException extends RuntimeException {

    public MailDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}

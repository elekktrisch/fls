package ch.alpenflight.platform.mail;

/**
 * Thrown when the SMTP send-path fails to dispatch a message (MIME-build or
 * transport failure). Unchecked — a caller (e.g. a notification job) decides
 * whether a single recipient failure aborts a batch or is logged and skipped.
 */
public class MailDispatchException extends RuntimeException {

    public MailDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}

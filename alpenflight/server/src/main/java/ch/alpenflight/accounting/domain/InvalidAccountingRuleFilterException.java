package ch.alpenflight.accounting.domain;

public class InvalidAccountingRuleFilterException extends RuntimeException {

    public InvalidAccountingRuleFilterException(String message) {
        super(message);
    }
}

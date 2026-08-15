package ch.alpenflight.accounting.application;

import java.util.UUID;

public class AccountingRuleFilterNotFoundException extends RuntimeException {

    public AccountingRuleFilterNotFoundException(UUID id) {
        super("AccountingRuleFilter not found in the caller's tenant: " + id);
    }
}

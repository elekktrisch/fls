package ch.alpenflight.referencedata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Accounting-rule-filter-type catalog (RECIPIENT, NO_LANDING_TAX, FLIGHT_TIME,
 * INSTRUCTOR_FEE, ADDITIONAL_FUEL_FEE, LANDING_TAX, VSF_FEE, ENGINE_TIME, …).
 * System-global; not tenant-scoped. Mapped to the V4
 * {@code t_accounting_rule_filter_type} table; data lives in the Flyway seed
 * (8 rows in V4; 5/55 land in T-09). Read-only.
 *
 * <p>{@code legacyIntId} (the legacy {@code AccountingRuleFilterTypeId} enum
 * value — 5/10/20/30/…) is EXPOSED on the wire here, unlike most reference
 * catalogs: the accounting edit form drives its conditional-section visibility
 * off the legacy int (article-target ∉{5,10}, recipient ==10, aircraft-filter
 * ==30, no-landing-tax ==20), so the SPA needs both the UUID id and the int.
 */
@Entity
@Table(name = "t_accounting_rule_filter_type")
public class AccountingRuleFilterType {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 50)
    private String code = "";

    @Column(name = "legacy_int_id", nullable = false)
    private short legacyIntId;

    @Column(name = "name", nullable = false, length = 100)
    private String name = "";

    @Column(name = "description")
    private @Nullable String description;

    protected AccountingRuleFilterType() {
        // JPA.
    }

    public @Nullable UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public short getLegacyIntId() {
        return legacyIntId;
    }

    public String getName() {
        return name;
    }

    public @Nullable String getDescription() {
        return description;
    }
}

package ch.alpenflight.referencedata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Accounting-unit-type catalog (MINUTES, SECONDS, LANDINGS, START_OR_FLIGHT).
 * System-global; not tenant-scoped. Mapped to the V4 {@code t_accounting_unit_type}
 * table; data lives in the V4 Flyway seed (4 rows). Read-only.
 *
 * <p>Drives the {@code AccountingUnitType} picker on the article-target section
 * of the accounting rule-filter edit form. {@code legacyIntId} (legacy
 * {@code AccountingUnitTypeId} — 10/20/30/40) is exposed alongside the UUID id.
 */
@Entity
@Table(name = "t_accounting_unit_type")
public class AccountingUnitType {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 50)
    private String code = "";

    @Column(name = "legacy_int_id", nullable = false)
    private short legacyIntId;

    @Column(name = "name", nullable = false, length = 100)
    private String name = "";

    @Column(name = "short_name", length = 30)
    private @Nullable String shortName;

    protected AccountingUnitType() {
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

    public @Nullable String getShortName() {
        return shortName;
    }
}

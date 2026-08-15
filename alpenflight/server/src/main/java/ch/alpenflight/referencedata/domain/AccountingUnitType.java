package ch.alpenflight.referencedata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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

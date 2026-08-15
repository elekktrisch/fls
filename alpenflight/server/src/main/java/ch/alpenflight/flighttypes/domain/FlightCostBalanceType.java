package ch.alpenflight.flighttypes.domain;

import ch.alpenflight.platform.id.FlightCostBalanceTypeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_flight_cost_balance_type")
public class FlightCostBalanceType {

    private static final int MAX_CODE_LENGTH = 48;
    private static final int MAX_DESCRIPTION_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = MAX_CODE_LENGTH)
    private String code = "";

    @Column(name = "legacy_int_id", nullable = false)
    private short legacyIntId;

    @Column(name = "description", nullable = false, length = MAX_DESCRIPTION_LENGTH)
    private String description = "";

    @Column(name = "person_for_invoice_required", nullable = false)
    private boolean personForInvoiceRequired;

    @Column(name = "is_for_glider", nullable = false)
    private boolean forGlider;

    @Column(name = "is_for_tow", nullable = false)
    private boolean forTow;

    @Column(name = "is_for_motor", nullable = false)
    private boolean forMotor;

    protected FlightCostBalanceType() {
    }

    public @Nullable FlightCostBalanceTypeId getId() {
        return FlightCostBalanceTypeId.ofNullable(id);
    }

    public String getCode() {
        return code;
    }

    public short getLegacyIntId() {
        return legacyIntId;
    }

    public String getDescription() {
        return description;
    }

    public boolean isPersonForInvoiceRequired() {
        return personForInvoiceRequired;
    }

    public boolean isForGlider() {
        return forGlider;
    }

    public boolean isForTow() {
        return forTow;
    }

    public boolean isForMotor() {
        return forMotor;
    }

    public void updateFlags(boolean newForGlider, boolean newForTow, boolean newForMotor) {
        if (!newForGlider && !newForTow && !newForMotor) {
            throw new FlightCostBalanceTypeInvariantException(
                    "at least one of isForGlider / isForTow / isForMotor must be true");
        }
        this.forGlider = newForGlider;
        this.forTow = newForTow;
        this.forMotor = newForMotor;
    }
}

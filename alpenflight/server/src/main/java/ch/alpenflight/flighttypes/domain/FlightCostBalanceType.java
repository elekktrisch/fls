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

/**
 * FlightCostBalanceType aggregate root. System-global reference catalogue of
 * cost-balance models (PILOT_PAYS_ALL, FIFTY_FIFTY_PILOT_COPILOT, …). No
 * tenant column — every tenant sees the same catalogue (S-047 reference-data
 * pattern). Per tenant-rules.yaml classified as {@code reference} so the
 * S-024 leakage sweep skips it.
 *
 * <p>Per ADR 0022 directive 2 the at-least-one-of-{glider,tow,motor}
 * invariant lives on the aggregate (constructor + {@link #updateFlags}); the
 * V3 schema dropped the {@code ck_fcbt_at_least_one_flag} CHECK exactly so
 * the rule could evolve in Java without a migration.
 *
 * <p>{@code legacyIntId} is preserved purely for the cutover importer's
 * legacy-id → UUID lookup; not exposed on the wire.
 *
 * <p>V3 dropped the legacy {@code IsActive} soft-deactivate flag — FCBT
 * mutation is full CRUD with physical DELETE gated by {@code ON DELETE
 * RESTRICT} from S-058 / S-072 consumer FKs. (Admin CRUD deferred to a
 * later story per S-053 design notes — no current consumer.)
 */
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
        // JPA.
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

    /**
     * Mutates the three "applies-to" flags atomically. Replaces V3's dropped
     * {@code ck_fcbt_at_least_one_flag} CHECK with an aggregate invariant:
     * at least one of glider / tow / motor must be true after the update.
     */
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

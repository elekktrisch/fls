package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The unit a rule emits its {@link DeliveryItemDetails} quantity in. The
 * numeric codes are the legacy {@code FLS.Data.WebApi.Accounting.AccountingUnitType}
 * values and are persisted/compared as-is, so they must stay stable.
 *
 * <p>{@link #quantityFrom} reproduces the legacy {@code GetUnitQuantity}
 * conversion and {@link #unitTypeString} the legacy {@code GetUnitTypeString}
 * German labels; both are bit-exact contracts the test harness diff depends on.
 */
public enum AccountingUnitType {
    UNDEFINED(0),
    MIN(10),
    SEC(20),
    LDGS(30),
    START_OR_FLIGHT(40);

    private static final BigDecimal SECONDS_PER_MINUTE = new BigDecimal(60);

    // Legacy GetUnitQuantity ran on C# `decimal` (28-29 significant digits,
    // banker's rounding). Sec→Min division is non-terminating for most flight
    // durations; this context reproduces that precision/rounding so a migrated
    // flight's quantity diffs bit-exact against the legacy output.
    private static final MathContext DECIMAL_DIVISION =
            new MathContext(28, RoundingMode.HALF_EVEN);

    private final int code;

    AccountingUnitType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static AccountingUnitType fromCode(int code) {
        for (AccountingUnitType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown AccountingUnitType code: " + code);
    }

    /**
     * Converts {@code quantity}, expressed in {@code baseUnit}, into this unit.
     * Time units convert by 60; count units (landings/starts) are
     * interchangeable; crossing the time↔count boundary is meaningless and
     * throws — the legacy {@code InvalidCastException} guard preserved as a
     * named test ({@code timeToCountUnitsThrows}).
     */
    public BigDecimal quantityFrom(BigDecimal quantity, AccountingUnitType baseUnit) {
        if (this == baseUnit) {
            return quantity;
        }
        return switch (this) {
            case MIN -> switch (baseUnit) {
                case SEC -> quantity.divide(SECONDS_PER_MINUTE, DECIMAL_DIVISION);
                default -> throw timeCountMismatch(baseUnit);
            };
            case SEC -> switch (baseUnit) {
                case MIN -> quantity.multiply(SECONDS_PER_MINUTE);
                default -> throw timeCountMismatch(baseUnit);
            };
            case LDGS, START_OR_FLIGHT -> switch (baseUnit) {
                case LDGS, START_OR_FLIGHT -> quantity;
                default -> throw timeCountMismatch(baseUnit);
            };
            case UNDEFINED -> quantity;
        };
    }

    /** The legacy German unit label used in the delivery line's {@code unitType}. */
    public String unitTypeString() {
        return switch (this) {
            case SEC -> "Sekunden";
            case MIN -> "Minuten";
            case START_OR_FLIGHT -> "Start";
            case LDGS -> "Landung";
            case UNDEFINED -> "";
        };
    }

    private IllegalArgumentException timeCountMismatch(AccountingUnitType baseUnit) {
        return new IllegalArgumentException(
                "Cannot convert quantity from " + baseUnit + " into " + this);
    }
}

package ch.alpenflight.accounting.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public enum AccountingUnitType {
    UNDEFINED(0),
    MIN(10),
    SEC(20),
    LDGS(30),
    START_OR_FLIGHT(40);

    private static final BigDecimal SECONDS_PER_MINUTE = new BigDecimal(60);

    private static final MathContext CSHARP_DECIMAL_DIVISION =
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

    public BigDecimal quantityFrom(BigDecimal quantity, AccountingUnitType baseUnit) {
        if (this == baseUnit) {
            return quantity;
        }
        return switch (this) {
            case MIN -> switch (baseUnit) {
                case SEC -> quantity.divide(SECONDS_PER_MINUTE, CSHARP_DECIMAL_DIVISION);
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

package ch.alpenflight.aircraft.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Aircraft immatriculation value object. Regulator-shape input ({@code A-Z},
 * {@code 0-9}, hyphen), 2-15 chars, case-folded uppercase on bind. The
 * canonical stored form is the user-entered value upper-cased; the
 * <em>normalized</em> form (dash-stripped uppercase) drives matching in the
 * accounting rules engine (R3) — both legacy and new code normalize on read.
 *
 * <p>Per ADR 0022 directive 2 the format invariant lives in this VO, not as
 * a DB CHECK constraint. V3's partial unique on
 * {@code immatriculation WHERE deleted_on IS NULL} is the structural
 * uniqueness guard (global, regulator-convention).
 */
public final class Immatriculation {

    public static final int MAX_LENGTH = 15;
    public static final int MIN_LENGTH = 2;

    private static final Pattern PATTERN = Pattern.compile("^[A-Z0-9-]{2,15}$");

    private final String stored;

    private Immatriculation(String stored) {
        this.stored = stored;
    }

    public static Immatriculation of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("immatriculation must not be null");
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("immatriculation must not be blank");
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.length() < MIN_LENGTH || upper.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "immatriculation must be " + MIN_LENGTH + "-" + MAX_LENGTH + " chars, got: " + raw);
        }
        if (!PATTERN.matcher(upper).matches()) {
            throw new IllegalArgumentException(
                    "immatriculation must match ^[A-Z0-9-]{2,15}$, got: " + raw);
        }
        return new Immatriculation(upper);
    }

    /** Stored form: trimmed, upper-cased; hyphens preserved. */
    public String normalized() {
        return stored;
    }

    /** Matching form for R3 rules-engine lookups: dash-stripped uppercase. */
    public static String forMatching(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("-", "").toUpperCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return stored;
    }
}

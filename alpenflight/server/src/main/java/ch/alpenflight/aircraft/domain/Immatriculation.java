package ch.alpenflight.aircraft.domain;

import java.util.Locale;
import java.util.regex.Pattern;

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

    public String normalized() {
        return stored;
    }

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

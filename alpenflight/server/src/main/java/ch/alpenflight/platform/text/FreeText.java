package ch.alpenflight.platform.text;

import org.jspecify.annotations.Nullable;

public final class FreeText {

    private FreeText() {
    }

    public static @Nullable String normalize(@Nullable String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException("text exceeds " + maxLength + " characters");
        }
        return trimmed;
    }
}

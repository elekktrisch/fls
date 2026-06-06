package ch.alpenflight.platform.text;

import org.jspecify.annotations.Nullable;

/**
 * Shared-kernel normalization for optional free-text aggregate fields
 * (info / remarks / notes). Centralizes the strip → blank-to-null → max-length
 * idiom every aggregate applied identically, so the rule lives in one place
 * (extracted at J-6 T-02 from the {@code AircraftReservation} /
 * {@code PlanningDay} {@code setInfo} clone).
 */
public final class FreeText {

    private FreeText() {
    }

    /**
     * Normalizes an optional free-text value: trims surrounding whitespace,
     * collapses an empty result to {@code null}, and rejects a value whose
     * trimmed length exceeds {@code maxLength}.
     *
     * @return the trimmed value, or {@code null} when null/blank
     * @throws IllegalArgumentException if the trimmed value exceeds
     *     {@code maxLength}
     */
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

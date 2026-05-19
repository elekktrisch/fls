package ch.alpenflight.nullaway;

/**
 * Deliberately violates @NullMarked rules. The {@code verifyNullAwayFailsOnViolation}
 * Gradle task compiles this in isolation and expects NullAway to reject it.
 *
 * <p>Do NOT "fix" this file. Its purpose is to prove AC3.
 */
public final class NullDereferenceDemo {

    private NullDereferenceDemo() {}

    /** Returns null inside a @NullMarked package without declaring it. NullAway: error. */
    public static String alwaysReturnsNull() {
        return null;
    }

    /** Dereferences a value known to be null. NullAway: dereference of null. */
    public static int demo() {
        String s = alwaysReturnsNull();
        return s.length();
    }
}

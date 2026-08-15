package ch.alpenflight.nullaway;

public final class NullDereferenceDemo {

    private NullDereferenceDemo() {}

    public static String alwaysReturnsNull() {
        return null;
    }

    public static int dereferencesNullSoThisSourceSetMustNotCompile() {
        String s = alwaysReturnsNull();
        return s.length();
    }
}

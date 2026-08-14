package ch.alpenflight.nullaway;

public final class NullDereferenceDemo {

    private NullDereferenceDemo() {}

    public static String alwaysReturnsNull() {
        return null;
    }

    public static int demo() {
        String s = alwaysReturnsNull();
        return s.length();
    }
}

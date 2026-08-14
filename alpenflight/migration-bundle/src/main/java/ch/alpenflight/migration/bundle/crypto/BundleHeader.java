package ch.alpenflight.migration.bundle.crypto;

@SuppressWarnings("ArrayRecordComponent")
public record BundleHeader(byte version, byte[] wrappedSessionKey) {

    private static final byte[] MAGIC_BYTES = {'A', 'L', 'P', 'F'};

    public static byte[] magic() {
        return MAGIC_BYTES.clone();
    }

    public static final int MAGIC_LENGTH = 4;

    private static final int VERSION_FIELD_BYTES = 1;
    private static final int WRAPPED_KEY_LEN_FIELD_BYTES = 2;

    private static final int VERSION_OFFSET = MAGIC_LENGTH;
    private static final int WRAPPED_KEY_LEN_OFFSET = VERSION_OFFSET + VERSION_FIELD_BYTES;

    public static final int FIXED_PREFIX_BYTES =
            MAGIC_LENGTH + VERSION_FIELD_BYTES + WRAPPED_KEY_LEN_FIELD_BYTES;

    public static final byte CURRENT_VERSION = 1;

    public static final int MAX_WRAPPED_KEY_LEN = 1024;

    public BundleHeader {
        if (wrappedSessionKey == null || wrappedSessionKey.length == 0) {
            throw new IllegalArgumentException("wrappedSessionKey must not be empty");
        }
    }

    public static BundleHeader parse(byte[] buffer) {
        if (buffer == null || buffer.length < FIXED_PREFIX_BYTES) {
            throw new MalformedHeaderException(
                    "Header buffer must hold at least " + FIXED_PREFIX_BYTES + " bytes");
        }
        for (int i = 0; i < MAGIC_LENGTH; i++) {
            if (buffer[i] != MAGIC_BYTES[i]) {
                throw new MalformedHeaderException(
                        "Header magic mismatch — expected ALPF, got "
                                + new String(buffer, 0, MAGIC_LENGTH, java.nio.charset.StandardCharsets.US_ASCII));
            }
        }
        byte version = buffer[VERSION_OFFSET];
        if (version != CURRENT_VERSION) {
            throw new MalformedHeaderException(
                    "Unsupported header version: " + version);
        }
        int wrappedKeyLen = bigEndianUint16At(buffer, WRAPPED_KEY_LEN_OFFSET);
        if (wrappedKeyLen <= 0 || wrappedKeyLen > MAX_WRAPPED_KEY_LEN) {
            throw new MalformedHeaderException(
                    "wrappedKeyLen out of range: " + wrappedKeyLen);
        }
        if (buffer.length < FIXED_PREFIX_BYTES + wrappedKeyLen) {
            throw new MalformedHeaderException(
                    "Header buffer truncated — declared wrappedKeyLen=" + wrappedKeyLen
                            + " but buffer holds only " + (buffer.length - FIXED_PREFIX_BYTES) + " trailing bytes");
        }
        byte[] wrappedSessionKey = new byte[wrappedKeyLen];
        System.arraycopy(buffer, FIXED_PREFIX_BYTES, wrappedSessionKey, 0, wrappedKeyLen);
        return new BundleHeader(version, wrappedSessionKey);
    }

    public static int peekWrappedKeyLen(byte[] fixedPrefix) {
        if (fixedPrefix == null || fixedPrefix.length < FIXED_PREFIX_BYTES) {
            throw new MalformedHeaderException(
                    "fixedPrefix buffer must hold at least " + FIXED_PREFIX_BYTES + " bytes");
        }
        for (int i = 0; i < MAGIC_LENGTH; i++) {
            if (fixedPrefix[i] != MAGIC_BYTES[i]) {
                throw new MalformedHeaderException(
                        "Header magic mismatch — expected ALPF, got "
                                + new String(fixedPrefix, 0, MAGIC_LENGTH, java.nio.charset.StandardCharsets.US_ASCII));
            }
        }
        return bigEndianUint16At(fixedPrefix, WRAPPED_KEY_LEN_OFFSET);
    }

    private static int bigEndianUint16At(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFF) << 8) | (buffer[offset + 1] & 0xFF);
    }

    public static final class MalformedHeaderException extends RuntimeException {
        public MalformedHeaderException(String message) {
            super(message);
        }
    }
}

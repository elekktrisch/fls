package ch.alpenflight.migration.bundle.crypto;

/**
 * Parsed S-141 bundle envelope header. Wire format (big-endian):
 *
 * <pre>
 *   MAGIC                "ALPF"  4 B
 *   HEADER_VERSION       1       1 B  (currently {@value #CURRENT_VERSION})
 *   WRAPPED_KEY_LEN      uint16  2 B
 *   WRAPPED_SESSION_KEY  bytes   wrappedKeyLen bytes (RSA-OAEP-SHA256
 *                                wrapped 32-byte AES-256 session key —
 *                                ~512 B for an RSA-4096 wrap)
 *   ----------- BODY FOLLOWS — caller streams it through StreamingAead --
 * </pre>
 *
 * <p>The parser is deliberately separate from the streaming pipeline so a
 * malformed header fails fast (single 7-byte plus 512-byte read from the
 * servlet input stream) before the application allocates the AES context
 * or borrows a database connection.
 */
@SuppressWarnings("ArrayRecordComponent")
public record BundleHeader(byte version, byte[] wrappedSessionKey) {

    /** ASCII bytes of the 4-byte magic prefix. Read-only — callers MUST NOT mutate. */
    private static final byte[] MAGIC_BYTES = {'A', 'L', 'P', 'F'};

    /** Defensive copy of the magic prefix — callers may freely mutate the returned array. */
    public static byte[] magic() {
        return MAGIC_BYTES.clone();
    }

    /** Length of the magic prefix in bytes. */
    public static final int MAGIC_LENGTH = 4;

    /** Minimum bytes the caller must buffer before {@link #parse} succeeds. */
    public static final int FIXED_PREFIX_BYTES = MAGIC_LENGTH + 1 + 2;

    public static final byte CURRENT_VERSION = 1;

    /** Sanity cap on the wrapped-session-key length — RSA-4096 wrap is ~512 B. */
    public static final int MAX_WRAPPED_KEY_LEN = 1024;

    public BundleHeader {
        if (wrappedSessionKey == null || wrappedSessionKey.length == 0) {
            throw new IllegalArgumentException("wrappedSessionKey must not be empty");
        }
    }

    /**
     * Parses the {@link #FIXED_PREFIX_BYTES} prefix + the wrapped-session-key
     * payload from a contiguous buffer. Callers (e.g. the streaming pipeline)
     * read {@code FIXED_PREFIX_BYTES} first, peek the {@code wrappedKeyLen},
     * read that many additional bytes, then assemble the full prefix buffer
     * and call this method.
     */
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
        byte version = buffer[MAGIC_LENGTH];
        if (version != CURRENT_VERSION) {
            throw new MalformedHeaderException(
                    "Unsupported header version: " + version);
        }
        int wrappedKeyLen = ((buffer[MAGIC_LENGTH + 1] & 0xFF) << 8)
                | (buffer[MAGIC_LENGTH + 2] & 0xFF);
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

    /**
     * Reads the wrappedKeyLen out of a freshly-read fixed-prefix buffer
     * without parsing the rest of the header. Lets the streaming pipeline
     * read exactly the right number of trailing bytes from the socket
     * before re-assembling the full header buffer for {@link #parse}.
     */
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
        return ((fixedPrefix[MAGIC_LENGTH + 1] & 0xFF) << 8)
                | (fixedPrefix[MAGIC_LENGTH + 2] & 0xFF);
    }

    /** Errors during header parse — translated by the exception handler to 400 / {@code BUNDLE_HEADER_MALFORMED}. */
    public static final class MalformedHeaderException extends RuntimeException {
        public MalformedHeaderException(String message) {
            super(message);
        }
    }
}

package ch.alpenflight.platform.id;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Crockford Base32 encoder / decoder for UUIDs. Produces 26 lowercase
 * characters from the alphabet {@code 0123456789abcdefghjkmnpqrstvwxyz}
 * (no {@code i}, {@code l}, {@code o}, {@code u} — visual ambiguity /
 * accidental-profanity). Decode accepts upper- and lowercase and tolerates
 * the ambiguous letters by mapping them to their canonical neighbours.
 *
 * <p>Used by typed-ID records ({@link ClubId}, future {@code PersonId} /
 * {@code UserId}) to produce the {@code &lt;prefix&gt;_&lt;26-char&gt;} external
 * form. The full 128 bits of the UUID survive the round-trip — no
 * information loss, no rendering ambiguity.
 */
final class IdEncoding {

    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
    private static final int[] LOOKUP = new int[128];

    static {
        java.util.Arrays.fill(LOOKUP, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            LOOKUP[ALPHABET[i]] = i;
            LOOKUP[Character.toUpperCase(ALPHABET[i])] = i;
        }
        // Tolerate the four ambiguous letters Crockford excludes from the alphabet.
        LOOKUP['i'] = 1; LOOKUP['I'] = 1;
        LOOKUP['l'] = 1; LOOKUP['L'] = 1;
        LOOKUP['o'] = 0; LOOKUP['O'] = 0;
        LOOKUP['u'] = LOOKUP['v']; LOOKUP['U'] = LOOKUP['v'];
    }

    private IdEncoding() {}

    static String encode(UUID uuid) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        byte[] bytes = buf.array();
        StringBuilder sb = new StringBuilder(26);
        long buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFFL);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(ALPHABET[(int) ((buffer >>> bits) & 0x1F)]);
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(int) ((buffer << (5 - bits)) & 0x1F)]);
        }
        return sb.toString();
    }

    static UUID decode(String text) {
        if (text == null || text.length() != 26) {
            throw new IllegalArgumentException(
                    "expected 26-character Crockford-base32 id payload, got: " + text);
        }
        long buffer = 0;
        int bits = 0;
        byte[] out = new byte[16];
        int outIdx = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int v = c < LOOKUP.length ? LOOKUP[c] : -1;
            if (v < 0) {
                throw new IllegalArgumentException(
                        "illegal character '" + c + "' at position " + i + " in id payload: " + text);
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out[outIdx++] = (byte) ((buffer >>> bits) & 0xFF);
            }
        }
        if (outIdx != 16) {
            throw new IllegalArgumentException("id payload did not decode to 16 bytes: " + text);
        }
        ByteBuffer bb = ByteBuffer.wrap(out);
        return new UUID(bb.getLong(), bb.getLong());
    }
}

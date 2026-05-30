package ch.alpenflight.migrations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link BundleHeader} wire-format parser. */
class BundleHeaderTest {

    @Test
    void parses_well_formed_header() {
        byte[] wrapped = new byte[512];
        wrapped[0] = (byte) 0xAB;
        wrapped[511] = (byte) 0xCD;
        byte[] buffer = buildHeader((byte) 1, wrapped);

        BundleHeader header = BundleHeader.parse(buffer);

        assertThat(header.version()).isEqualTo((byte) 1);
        assertThat(header.wrappedSessionKey()).isEqualTo(wrapped);
    }

    @Test
    void magic_mismatch_throws() {
        byte[] buffer = new byte[BundleHeader.FIXED_PREFIX_BYTES + 1];
        buffer[0] = 'X';
        buffer[1] = 'X';
        buffer[2] = 'X';
        buffer[3] = 'X';
        assertThatExceptionOfType(BundleHeader.MalformedHeaderException.class)
                .isThrownBy(() -> BundleHeader.parse(buffer))
                .withMessageContaining("magic mismatch");
    }

    @Test
    void unsupported_version_throws() {
        byte[] buffer = buildHeader((byte) 2, new byte[]{1});
        assertThatExceptionOfType(BundleHeader.MalformedHeaderException.class)
                .isThrownBy(() -> BundleHeader.parse(buffer))
                .withMessageContaining("Unsupported header version: 2");
    }

    @Test
    void zero_wrapped_key_len_throws() {
        byte[] buffer = new byte[BundleHeader.FIXED_PREFIX_BYTES];
        System.arraycopy(BundleHeader.magic(), 0, buffer, 0, 4);
        buffer[4] = 1;
        buffer[5] = 0;
        buffer[6] = 0;
        assertThatExceptionOfType(BundleHeader.MalformedHeaderException.class)
                .isThrownBy(() -> BundleHeader.parse(buffer))
                .withMessageContaining("out of range");
    }

    @Test
    void over_cap_wrapped_key_len_throws() {
        byte[] buffer = new byte[BundleHeader.FIXED_PREFIX_BYTES];
        System.arraycopy(BundleHeader.magic(), 0, buffer, 0, 4);
        buffer[4] = 1;
        int len = BundleHeader.MAX_WRAPPED_KEY_LEN + 1;
        buffer[5] = (byte) ((len >>> 8) & 0xFF);
        buffer[6] = (byte) (len & 0xFF);
        assertThatExceptionOfType(BundleHeader.MalformedHeaderException.class)
                .isThrownBy(() -> BundleHeader.parse(buffer))
                .withMessageContaining("out of range");
    }

    @Test
    void truncated_buffer_throws() {
        byte[] full = buildHeader((byte) 1, new byte[10]);
        byte[] truncated = new byte[full.length - 5];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        assertThatExceptionOfType(BundleHeader.MalformedHeaderException.class)
                .isThrownBy(() -> BundleHeader.parse(truncated))
                .withMessageContaining("truncated");
    }

    @Test
    void peek_returns_declared_wrapped_key_len() {
        byte[] full = buildHeader((byte) 1, new byte[123]);
        int len = BundleHeader.peekWrappedKeyLen(full);
        assertThat(len).isEqualTo(123);
    }

    private static byte[] buildHeader(byte version, byte[] wrappedSessionKey) {
        byte[] buffer = new byte[BundleHeader.FIXED_PREFIX_BYTES + wrappedSessionKey.length];
        System.arraycopy(BundleHeader.magic(), 0, buffer, 0, 4);
        buffer[4] = version;
        buffer[5] = (byte) ((wrappedSessionKey.length >>> 8) & 0xFF);
        buffer[6] = (byte) (wrappedSessionKey.length & 0xFF);
        System.arraycopy(wrappedSessionKey, 0, buffer, BundleHeader.FIXED_PREFIX_BYTES, wrappedSessionKey.length);
        return buffer;
    }
}

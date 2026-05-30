package ch.alpenflight.migrations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link SecureBytes}. */
class SecureBytesTest {

    @Test
    void close_zeros_the_captive_array() {
        byte[] material = {1, 2, 3, 4, 5};
        SecureBytes secret = new SecureBytes(material);

        assertThat(secret.length()).isEqualTo(5);
        secret.close();

        assertThat(material).containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    void second_close_is_a_no_op() {
        byte[] material = {7, 8, 9};
        SecureBytes secret = new SecureBytes(material);
        secret.close();
        secret.close();
        assertThat(material).containsExactly(0, 0, 0);
    }

    @Test
    void bytes_after_close_throws() {
        SecureBytes secret = new SecureBytes(new byte[]{42});
        secret.close();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(secret::bytes);
    }

    @Test
    void null_material_throws_at_construction() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SecureBytes(null));
    }
}

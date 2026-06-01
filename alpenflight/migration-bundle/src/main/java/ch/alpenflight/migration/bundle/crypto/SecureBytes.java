package ch.alpenflight.migration.bundle.crypto;

import java.util.Arrays;

/**
 * AutoCloseable wrapper around secret bytes (the unwrapped RSA private key
 * or the AES-256 session key). {@link #close} zeros the underlying array
 * on every exit path — happy, exception, txn-rollback, interrupt. Callers
 * use {@code try-with-resources} so the wipe happens deterministically;
 * Vision NFR ≤ 60 s wipe is satisfied inline (typically µs).
 *
 * <p>The captive array is exposed through {@link #bytes} for the small
 * number of JDK APIs that need a {@code byte[]} (RSA-OAEP unwrap,
 * StreamingAead key material). Callers MUST NOT retain the reference past
 * the try-with-resources scope.
 */
public final class SecureBytes implements AutoCloseable {

    private final byte[] material;
    private boolean closed;

    public SecureBytes(byte[] material) {
        if (material == null) {
            throw new IllegalArgumentException("material must not be null");
        }
        this.material = material;
    }

    public byte[] bytes() {
        if (closed) {
            throw new IllegalStateException("SecureBytes already closed");
        }
        return material;
    }

    public int length() {
        return material.length;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        Arrays.fill(material, (byte) 0);
        closed = true;
    }
}

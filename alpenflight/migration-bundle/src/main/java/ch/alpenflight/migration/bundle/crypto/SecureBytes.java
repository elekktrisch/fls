package ch.alpenflight.migration.bundle.crypto;

import java.util.Arrays;

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

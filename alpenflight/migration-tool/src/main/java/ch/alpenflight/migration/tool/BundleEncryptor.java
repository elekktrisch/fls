package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.crypto.BundleHeader;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migration.bundle.crypto.SecureBytes;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the ALPF wire-format envelope — the byte-exact inverse of the
 * server's decrypt path. Layout (big-endian), matching
 * {@code BundleHeader} + {@code MigrationBundleTestFactory}:
 *
 * <pre>
 *   "ALPF"                       4 B   BundleHeader.magic()
 *   version                      1 B   BundleHeader.CURRENT_VERSION
 *   wrappedKeyLen     uint16 BE  2 B   wrappedSessionKey.length
 *   wrappedSessionKey            N B   cipher.wrapSessionKey(pubDer, sessionKey)
 *   encryptedBody     stream           cipher.newEncryptingStream(sessionKey,
 *                                       uploadId, sink) over the tar.gz plaintext
 * </pre>
 *
 * <p>The 32-byte AES-256 session key is freshly {@link SecureRandom}-drawn,
 * wrapped under the handshake RSA-4096 public key (RSA-OAEP-SHA256), and held
 * in {@link SecureBytes} so it zeros on every exit path.
 *
 * <p>Output hygiene: written to a {@code 0600} sibling temp file, then
 * atomically renamed onto {@code --output}; a partial write is deleted on
 * failure so a half-encrypted file is never left behind.
 */
public final class BundleEncryptor {

    private static final int SESSION_KEY_BYTES = 32;

    private final MigrationBundleCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public BundleEncryptor(MigrationBundleCipher cipher) {
        this.cipher = cipher;
    }

    /**
     * Encrypt {@code plaintextTarGz} into {@code output} under the handshake.
     *
     * @param output         final destination ({@code --output}).
     * @param plaintextTarGz the bundle plaintext temp file (tar.gz).
     * @param uploadId       StreamingAead AAD.
     * @param publicKeyDer   RSA-4096 SPKI DER for the session-key wrap.
     */
    public void encryptTo(Path output, Path plaintextTarGz, UUID uploadId, byte[] publicKeyDer) {
        Path tmp = siblingTemp(output);
        byte[] sessionKey = new byte[SESSION_KEY_BYTES];
        random.nextBytes(sessionKey);
        try (SecureBytes session = new SecureBytes(sessionKey)) {
            byte[] wrapped = cipher.wrapSessionKey(publicKeyDer, session.bytes());
            writeEnvelope(tmp, plaintextTarGz, uploadId, session, wrapped);
            Files.move(tmp, output,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            BundleWriter.deleteQuietly(tmp);
            if (e instanceof ExportException ee) {
                throw ee;
            }
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed to encrypt bundle: " + e.getMessage(), e);
        }
    }

    private void writeEnvelope(Path tmp, Path plaintextTarGz, UUID uploadId,
                               SecureBytes session, byte[] wrapped) throws IOException {
        createSecureFile(tmp);
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(tmp))) {
            raw.write(BundleHeader.magic());
            raw.write(BundleHeader.CURRENT_VERSION);
            raw.write((wrapped.length >>> 8) & 0xFF);
            raw.write(wrapped.length & 0xFF);
            raw.write(wrapped);
            // The encrypting stream wraps `raw`; closing it flushes the final
            // AEAD frame. Do NOT let it close `raw` before the header flushes —
            // header bytes are already written above, so frame + close order is
            // header → body, matching the server's read order.
            try (OutputStream body = cipher.newEncryptingStream(session, uploadId, raw);
                 InputStream plaintext = Files.newInputStream(plaintextTarGz)) {
                plaintext.transferTo(body);
            }
        }
    }

    private static Path siblingTemp(Path output) {
        Path parent = output.toAbsolutePath().getParent();
        String name = "." + output.getFileName() + ".partial-" + UUID.randomUUID();
        return parent.resolve(name);
    }

    /** Create the file 0600 up front so plaintext-adjacent ciphertext is never world-readable. */
    private static void createSecureFile(Path file) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException nonPosix) {
            // Non-POSIX FS (e.g. Windows) — fall back to default create; the
            // operator runs the export on a controlled host.
            Files.createFile(file);
        }
    }
}

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

public final class BundleEncryptor {

    private static final int SESSION_KEY_BYTES = 32;

    private final MigrationBundleCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public BundleEncryptor(MigrationBundleCipher cipher) {
        this.cipher = cipher;
    }

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

    private static void createSecureFile(Path file) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException nonPosix) {
            Files.createFile(file);
        }
    }
}

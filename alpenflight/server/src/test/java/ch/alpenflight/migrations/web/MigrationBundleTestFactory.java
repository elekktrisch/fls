package ch.alpenflight.migrations.web;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.migrations.domain.BundleHeader;
import ch.alpenflight.migrations.domain.MigrationBundleCipher;
import ch.alpenflight.migrations.domain.SecureBytes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Test-only producer for S-141 ALPF wire-format bundles. Mirrors the
 * envelope shape S-139's JAR will emit; the integration tests use it as
 * a stand-in until the JAR ships.
 *
 * <p>Lives in {@code src/test/java}; ArchUnit's disk-sink ban applies
 * only to production code, so this class is free to use
 * {@link ByteArrayOutputStream}.
 */
final class MigrationBundleTestFactory {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private MigrationBundleTestFactory() { }

    /**
     * Build a minimal ALPF bundle for the walking-skeleton integration
     * test: declare every EntityType as unmapped (no NDJSON entries),
     * declare a single Club in the manifest, encrypt under the upload's
     * RSA public key.
     */
    static byte[] buildWalkingSkeletonBundle(MigrationBundleCipher cipher,
                                             UUID uploadId,
                                             byte[] rsaPublicKeyDer,
                                             String deploymentName,
                                             BundleManifest.ClubDeclaration soleClub) throws IOException {
        Map<EntityType, String> unmappedReason = new EnumMap<>(EntityType.class);
        for (EntityType type : EntityType.values()) {
            unmappedReason.put(type, "WALKING_SKELETON");
        }
        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                deploymentName,
                List.of(soleClub),
                null,
                Map.of(),
                unmappedReason);
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
        byte[] tarGzPlaintext = wrapInTarGz(manifestBytes);

        byte[] sessionKey = new byte[32];
        RANDOM.nextBytes(sessionKey);
        byte[] wrappedSessionKey = cipher.wrapSessionKey(rsaPublicKeyDer, sessionKey);
        ByteArrayOutputStream encryptedBody = new ByteArrayOutputStream();
        try (SecureBytes sessionBytes = new SecureBytes(sessionKey);
             OutputStream encryptingStream = cipher.newEncryptingStream(sessionBytes, uploadId, encryptedBody)) {
            encryptingStream.write(tarGzPlaintext);
        }
        ByteArrayOutputStream wireFormat = new ByteArrayOutputStream();
        wireFormat.write(BundleHeader.magic());
        wireFormat.write(BundleHeader.CURRENT_VERSION);
        wireFormat.write((wrappedSessionKey.length >>> 8) & 0xFF);
        wireFormat.write(wrappedSessionKey.length & 0xFF);
        wireFormat.write(wrappedSessionKey);
        wireFormat.write(encryptedBody.toByteArray());
        return wireFormat.toByteArray();
    }

    private static byte[] wrapInTarGz(byte[] manifestBytes) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(sink);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry manifestEntry = new TarArchiveEntry("manifest.json");
            manifestEntry.setSize(manifestBytes.length);
            tarOut.putArchiveEntry(manifestEntry);
            tarOut.write(manifestBytes);
            tarOut.closeArchiveEntry();
            tarOut.finish();
        }
        return sink.toByteArray();
    }
}

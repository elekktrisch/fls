package ch.alpenflight.migrations.web;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migrations.application.BundleManifest;
import ch.alpenflight.migration.bundle.crypto.BundleHeader;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migration.bundle.crypto.SecureBytes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

final class MigrationBundleTestFactory {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private MigrationBundleTestFactory() { }

    static byte[] buildWalkingSkeletonBundle(MigrationBundleCipher cipher,
                                             UUID uploadId,
                                             byte[] rsaPublicKeyDer,
                                             String deploymentName,
                                             BundleManifest.ClubDeclaration soleClub) throws IOException {
        BundleManifest manifest = manifestWithAllUnmapped(deploymentName, List.of(soleClub));
        return encryptedBundleWithEntries(cipher, uploadId, rsaPublicKeyDer, manifest,
                Map.of());
    }

    static byte[] buildMultiClubSkeletonBundle(MigrationBundleCipher cipher,
                                               UUID uploadId,
                                               byte[] rsaPublicKeyDer,
                                               String deploymentName,
                                               List<BundleManifest.ClubDeclaration> clubs)
            throws IOException {
        BundleManifest manifest = manifestWithAllUnmapped(deploymentName, clubs);
        return encryptedBundleWithEntries(cipher, uploadId, rsaPublicKeyDer, manifest,
                Map.of());
    }

    static byte[] buildBundleWithEntries(MigrationBundleCipher cipher,
                                         UUID uploadId,
                                         byte[] rsaPublicKeyDer,
                                         String deploymentName,
                                         List<BundleManifest.ClubDeclaration> clubs,
                                         Map<EntityType, EntityPolicy> entityPolicies,
                                         Map<String, byte[]> tarEntriesAfterManifest) throws IOException {
        Map<EntityType, String> unmappedReason = new EnumMap<>(EntityType.class);
        for (EntityType type : EntityType.values()) {
            if (!entityPolicies.containsKey(type)) {
                unmappedReason.put(type, "TEST_OMITTED");
            }
        }
        BundleManifest manifest = new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                deploymentName,
                clubs,
                null,
                entityPolicies,
                unmappedReason);
        return encryptedBundleWithEntries(cipher, uploadId, rsaPublicKeyDer, manifest,
                tarEntriesAfterManifest);
    }

    static byte[] buildEmptyClubsBundle(MigrationBundleCipher cipher,
                                        UUID uploadId,
                                        byte[] rsaPublicKeyDer,
                                        String deploymentName) throws IOException {
        BundleManifest manifest = manifestWithAllUnmapped(deploymentName, List.of());
        return encryptedBundleWithEntries(cipher, uploadId, rsaPublicKeyDer, manifest,
                Map.of());
    }

    static byte[] buildHeaderOnlyPrefix(int wrappedKeyLenField) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(BundleHeader.magic());
            out.write(BundleHeader.CURRENT_VERSION);
            out.write((wrappedKeyLenField >>> 8) & 0xFF);
            out.write(wrappedKeyLenField & 0xFF);
        } catch (IOException unreachable) {
            throw new IllegalStateException(unreachable);
        }
        return out.toByteArray();
    }

    static byte[] truncatedBundle(byte[] completeBundle, int suffixBytesToDrop) {
        if (suffixBytesToDrop <= 0 || suffixBytesToDrop >= completeBundle.length) {
            throw new IllegalArgumentException(
                    "suffixBytesToDrop must be in (0, " + completeBundle.length + "), got "
                            + suffixBytesToDrop);
        }
        return Arrays.copyOf(completeBundle, completeBundle.length - suffixBytesToDrop);
    }

    static byte[] buildBundleWithRawTarEntry(MigrationBundleCipher cipher,
                                             UUID uploadId,
                                             byte[] rsaPublicKeyDer,
                                             String deploymentName,
                                             BundleManifest.ClubDeclaration soleClub,
                                             String hostileEntryName,
                                             byte[] hostilePayload) throws IOException {
        BundleManifest manifest = manifestWithAllUnmapped(deploymentName, List.of(soleClub));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(hostileEntryName, hostilePayload);
        return encryptedBundleWithEntries(cipher, uploadId, rsaPublicKeyDer, manifest, entries);
    }

    private static BundleManifest manifestWithAllUnmapped(
            String deploymentName, List<BundleManifest.ClubDeclaration> clubs) {
        Map<EntityType, String> unmappedReason = new EnumMap<>(EntityType.class);
        for (EntityType type : EntityType.values()) {
            unmappedReason.put(type, "TEST_OMITTED");
        }
        return new BundleManifest(
                Manifest.CURRENT_SCHEMA_VERSION,
                deploymentName,
                clubs,
                null,
                Map.of(),
                unmappedReason);
    }

    static byte[] encryptTarGzPlaintext(MigrationBundleCipher cipher,
                                        UUID uploadId,
                                        byte[] rsaPublicKeyDer,
                                        byte[] tarGzPlaintext) throws IOException {
        byte[] sessionKey = new byte[32];
        RANDOM.nextBytes(sessionKey);
        byte[] wrappedSessionKey = cipher.wrapSessionKey(rsaPublicKeyDer, sessionKey);
        ByteArrayOutputStream encryptedBody = new ByteArrayOutputStream();
        try (SecureBytes sessionBytes = new SecureBytes(sessionKey);
             OutputStream encryptingStream =
                     cipher.newEncryptingStream(sessionBytes, uploadId, encryptedBody)) {
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

    private static byte[] encryptedBundleWithEntries(MigrationBundleCipher cipher,
                                                     UUID uploadId,
                                                     byte[] rsaPublicKeyDer,
                                                     BundleManifest manifest,
                                                     Map<String, byte[]> tarEntriesAfterManifest)
            throws IOException {
        byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
        byte[] tarGzPlaintext = wrapInTarGz(manifestBytes, tarEntriesAfterManifest);

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

    private static byte[] wrapInTarGz(byte[] manifestBytes,
                                      Map<String, byte[]> tarEntriesAfterManifest) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(sink);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            writeTarEntry(tarOut, "manifest.json", manifestBytes);
            for (Map.Entry<String, byte[]> entry : tarEntriesAfterManifest.entrySet()) {
                writeTarEntry(tarOut, entry.getKey(), entry.getValue());
            }
            tarOut.finish();
        }
        return sink.toByteArray();
    }

    private static void writeTarEntry(TarArchiveOutputStream tarOut,
                                      String name, byte[] payload) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(payload.length);
        tarOut.putArchiveEntry(entry);
        tarOut.write(payload);
        tarOut.closeArchiveEntry();
    }
}

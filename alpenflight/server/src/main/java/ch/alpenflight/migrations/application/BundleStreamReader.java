package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.crypto.BundleHeader;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

final class BundleStreamReader {

    private static final String MANIFEST_ENTRY_NAME = "manifest.json";
    private static final ObjectMapper JSON = buildHardenedNonClosingJsonMapper();

    BundleStreamReader() { }

    BundleHeader readHeader(InputStream encryptedBody) {
        byte[] fixedPrefix = readExactly(encryptedBody, BundleHeader.FIXED_PREFIX_BYTES);
        try {
            int wrappedKeyLen = BundleHeader.peekWrappedKeyLen(fixedPrefix);
            if (wrappedKeyLen > BundleHeader.MAX_WRAPPED_KEY_LEN || wrappedKeyLen <= 0) {
                throw new BundleHeader.MalformedHeaderException(
                        "wrappedKeyLen out of range: " + wrappedKeyLen);
            }
            byte[] wrappedKey = readExactly(encryptedBody, wrappedKeyLen);
            byte[] fullHeader = new byte[fixedPrefix.length + wrappedKey.length];
            System.arraycopy(fixedPrefix, 0, fullHeader, 0, fixedPrefix.length);
            System.arraycopy(wrappedKey, 0, fullHeader, fixedPrefix.length, wrappedKey.length);
            return BundleHeader.parse(fullHeader);
        } catch (BundleHeader.MalformedHeaderException malformed) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_HEADER_MALFORMED,
                    String.valueOf(malformed.getMessage()), malformed);
        }
    }

    BundleManifest readManifestOrThrow(TarArchiveInputStream tar) throws IOException {
        TarArchiveEntry entry = tar.getNextEntry();
        if (entry == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_MISSING_ENTRIES,
                    "Bundle is empty — manifest.json must be entry 0");
        }
        if (!MANIFEST_ENTRY_NAME.equals(entry.getName())) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_MISSING_ENTRIES,
                    "First tar entry must be manifest.json, got " + entry.getName());
        }
        try {
            return JSON.readValue(tar, BundleManifest.class);
        } catch (IOException ioOrParse) {
            if (ioOrParse instanceof JsonProcessingException malformed) {
                String location = malformed.getLocation() == null
                        ? "(unknown)"
                        : "line " + malformed.getLocation().getLineNr()
                                + " col " + malformed.getLocation().getColumnNr();
                throw new BundleIngestException(
                        BundleIngestErrorCode.MANIFEST_INVALID,
                        "Manifest JSON failed to parse at " + location, malformed);
            }
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_TAR_PARSE_FAILED,
                    "I/O failure reading manifest entry", ioOrParse);
        } catch (IllegalArgumentException invalid) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.MANIFEST_INVALID,
                    "Manifest rejected: " + invalid.getMessage(), invalid);
        }
    }

    static void rejectUnsafeTarName(String name) {
        if (name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_TAR_PARSE_FAILED,
                    "Tar entry name rejected (filesystem-traversal defense): " + name);
        }
    }

    private static byte[] readExactly(InputStream from, int count) {
        byte[] buffer = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read;
            try {
                read = from.read(buffer, offset, count - offset);
            } catch (IOException ioFailure) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_TRUNCATED,
                        "I/O error reading bundle header", ioFailure);
            }
            if (read == -1) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_TRUNCATED,
                        "EOF before " + count + " bytes were read from the bundle prefix");
            }
            offset += read;
        }
        return buffer;
    }

    private static ObjectMapper buildHardenedNonClosingJsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        StreamReadConstraints hardened = StreamReadConstraints.builder()
                .maxStringLength(1_000_000)
                .maxNumberLength(1000)
                .maxNestingDepth(50)
                .build();
        mapper.getFactory().setStreamReadConstraints(hardened);
        mapper.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
        return mapper;
    }

    static final class NonClosingInputStream extends InputStream {

        private final InputStream delegate;

        NonClosingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() {
        }
    }

    static final class NonClosingBufferedReader implements AutoCloseable {

        private final byte[] buffer = new byte[8192];
        private int bufferOffset;
        private int bufferLength;
        private final InputStream delegate;

        private NonClosingBufferedReader(InputStream delegate) {
            this.delegate = delegate;
        }

        static NonClosingBufferedReader of(InputStream delegate) {
            return new NonClosingBufferedReader(delegate);
        }

        @org.jspecify.annotations.Nullable String readLine() throws IOException {
            StringBuilder line = new StringBuilder();
            while (true) {
                if (bufferOffset >= bufferLength) {
                    int read = delegate.read(buffer, 0, buffer.length);
                    if (read == -1) {
                        return line.length() == 0 ? null : line.toString();
                    }
                    bufferOffset = 0;
                    bufferLength = read;
                }
                while (bufferOffset < bufferLength) {
                    byte b = buffer[bufferOffset++];
                    if (b == '\n') {
                        return line.toString();
                    }
                    if (b == '\r') {
                        continue;
                    }
                    line.append((char) (b & 0xFF));
                }
            }
        }

        @Override
        public void close() {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    static ObjectMapper sharedHardenedJsonMapper() {
        return JSON;
    }
}

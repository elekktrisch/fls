package ch.alpenflight.migration.bundle.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

public final class BundleStream {

    public static final String ENTITIES_DIRECTORY = "entities/";

    public static final String MANIFEST_ENTRY_NAME = "manifest.json";

    private BundleStream() { }

    public static byte[] writeTarGz(Map<String, byte[]> entityNdjsonByName, byte[] manifestBytes)
            throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(sink);
                TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            writeEntry(tarOut, MANIFEST_ENTRY_NAME, manifestBytes);
            for (Map.Entry<String, byte[]> entry : entityNdjsonByName.entrySet()) {
                String tarEntryName = ENTITIES_DIRECTORY + entry.getKey() + ".ndjson";
                writeEntry(tarOut, tarEntryName, entry.getValue());
            }
            tarOut.finish();
        }
        return sink.toByteArray();
    }

    private static void writeEntry(TarArchiveOutputStream tarOut, String name, byte[] payload)
            throws IOException {
        TarArchiveEntry tarEntry = new TarArchiveEntry(name);
        tarEntry.setSize(payload.length);
        tarOut.putArchiveEntry(tarEntry);
        tarOut.write(payload);
        tarOut.closeArchiveEntry();
    }

    public static Parsed readTarGz(byte[] tarGzBytes, ObjectMapper json) throws IOException {
        Map<String, List<JsonNode>> entityRowsByName = new LinkedHashMap<>();
        JsonNode manifestNode = null;
        try (InputStream byteSource = new ByteArrayInputStream(tarGzBytes);
                GZIPInputStream gzipIn = new GZIPInputStream(byteSource);
                TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry tarEntry;
            while ((tarEntry = tarIn.getNextEntry()) != null) {
                if (tarEntry.isDirectory()) {
                    continue;
                }
                byte[] entryBytes = tarIn.readAllBytes();
                String entryName = tarEntry.getName();
                if (MANIFEST_ENTRY_NAME.equals(entryName)) {
                    manifestNode = json.readTree(entryBytes);
                } else if (entryName.startsWith(ENTITIES_DIRECTORY) && entryName.endsWith(".ndjson")) {
                    String entityName = entryName.substring(
                            ENTITIES_DIRECTORY.length(),
                            entryName.length() - ".ndjson".length());
                    entityRowsByName.put(entityName, parseNdjson(entryBytes, json));
                }
            }
        }
        if (manifestNode == null) {
            throw new IOException("tar.gz bundle missing " + MANIFEST_ENTRY_NAME);
        }
        return new Parsed(manifestNode, entityRowsByName);
    }

    private static List<JsonNode> parseNdjson(byte[] ndjsonBytes, ObjectMapper json)
            throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        String text = new String(ndjsonBytes, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            rows.add(json.readTree(trimmed));
        }
        return rows;
    }

    public record Parsed(JsonNode manifest, Map<String, List<JsonNode>> entityRowsByName) {
    }
}

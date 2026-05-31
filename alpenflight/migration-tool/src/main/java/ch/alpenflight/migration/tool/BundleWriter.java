package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Streams the bundle plaintext (a {@code tar.gz}) to a temp file, with no
 * whole-bundle buffering:
 *
 * <ol>
 *   <li>Per registered entity, drain its forward-only {@link ResultSet}
 *       through {@link Mapper#writeNdjson} into a per-entity temp NDJSON
 *       file, hashing (sha256) + counting rows as bytes are written.</li>
 *   <li>For {@code FULL_PORT} entities, emit
 *       {@code legacy_id_map/<E>.pgcopy} — a binary PGCOPY identity map
 *       ({@code legacy_guid -> legacy_guid}) the consumer COPYs into
 *       {@code legacy_id_map_<entity>} so same-entity FK rewrites resolve.</li>
 *   <li>Assemble the final tar (manifest.json entry 0, then the entity
 *       NDJSON streams, then the pgcopy streams) piped through gzip into the
 *       output temp file.</li>
 * </ol>
 *
 * <p>Tar entry names match the server ingest contract: {@code <E>.ndjson}
 * (mapped via {@code EntityType.valueOf}) and {@code legacy_id_map/<E>.pgcopy}
 * (the {@code COPY legacy_id_map_<E>} target folds to lowercase in Postgres).
 */
public final class BundleWriter {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LegacyJdbcReader reader;
    private final Path workDir;
    private final boolean verbose;

    public BundleWriter(LegacyJdbcReader reader, Path workDir, boolean verbose) {
        this.reader = reader;
        this.workDir = workDir;
        this.verbose = verbose;
    }

    /**
     * Stream every registered entity to NDJSON temp files. Returns the
     * per-entity results (path / row count / sha256) in registry order. The
     * caller uses these for stats and, unless {@code --dry-run}, hands them
     * to {@link #assembleTarGz}.
     */
    public List<EntityStreamResult> streamEntities(List<EntityType> entities) {
        Map<EntityType, Mapper> mappers = mappersByType();
        List<EntityStreamResult> results = new ArrayList<>();
        for (EntityType entity : entities) {
            Mapper mapper = mappers.get(entity);
            if (mapper == null) {
                throw new ExportException(ExitCode.IO_ERROR,
                        "No KnownMappers entry for registered entity " + entity);
            }
            results.add(streamOne(entity, mapper));
        }
        return results;
    }

    private EntityStreamResult streamOne(EntityType entity, Mapper mapper) {
        Path ndjson = temp(entity.name() + "-ndjson");
        String sql = MapperLegacyBindings.selectForProducer(entity);
        long rows = 0;
        MessageDigest digest = newSha256();
        try (ResultSet rs = reader.openEntityCursor(sql);
             OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(ndjson));
             DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest)) {
            while (rs.next()) {
                try (JsonGenerator gen = JSON_FACTORY.createGenerator(digestOut)) {
                    mapper.writeNdjson(rs, gen);
                }
                digestOut.write('\n');
                rows++;
            }
        } catch (SQLException | IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed streaming entity " + entity + ": " + e.getMessage(), e);
        }
        String hex = HexFormat.of().formatHex(digest.digest());
        if (verbose) {
            System.err.printf("  %-26s %8d rows  sha256=%s%n", entity, rows, hex);
        }
        return new EntityStreamResult(entity, ndjson, rows, hex);
    }

    /**
     * Build the {@code legacy_id_map/<E>.pgcopy} identity map for a FULL_PORT
     * entity by re-reading the legacy_guid of every NDJSON line. Bounded
     * per-entity read (not whole-bundle); the NDJSON temp file already exists.
     */
    Path writeIdentityPgcopy(EntityStreamResult ndjsonResult) {
        Path pgcopy = temp(ndjsonResult.entityType().name() + "-pgcopy");
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(pgcopy));
             LegacyIdMapWriter writer = new LegacyIdMapWriter(fileOut);
             BufferedReader lines = Files.newBufferedReader(
                     ndjsonResult.ndjsonTempFile(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = JSON.readTree(line);
                JsonNode guid = row.get("legacy_guid");
                if (guid == null || !guid.isTextual()) {
                    throw new ExportException(ExitCode.IO_ERROR,
                            "FULL_PORT NDJSON row for " + ndjsonResult.entityType()
                                    + " has no legacy_guid; cannot build identity map");
                }
                UUID id = UUID.fromString(guid.asText());
                writer.write(id, id);
            }
        } catch (IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed writing identity pgcopy for " + ndjsonResult.entityType()
                            + ": " + e.getMessage(), e);
        }
        return pgcopy;
    }

    /**
     * Assemble manifest + entity streams + identity pgcopy maps into a
     * gzip-compressed tar at {@code destination}. Entry order: manifest.json,
     * then each entity's NDJSON, then each FULL_PORT entity's pgcopy.
     */
    public void assembleTarGz(byte[] manifestBytes,
                              List<EntityStreamResult> entityResults,
                              Path destination) {
        // FULL_PORT identity maps, keyed by tar entry name.
        Map<String, Path> pgcopyEntries = new LinkedHashMap<>();
        for (EntityStreamResult result : entityResults) {
            if (MapperLegacyBindings.portPolicy(result.entityType())
                    == MapperLegacyBindings.PortPolicy.FULL_PORT) {
                pgcopyEntries.put(
                        "legacy_id_map/" + result.entityType().name() + ".pgcopy",
                        writeIdentityPgcopy(result));
            }
        }
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(destination));
             GZIPOutputStream gzip = new GZIPOutputStream(fileOut);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            putBytesEntry(tar, "manifest.json", manifestBytes);
            for (EntityStreamResult result : entityResults) {
                putFileEntry(tar, result.tarEntryName(), result.ndjsonTempFile());
            }
            for (Map.Entry<String, Path> entry : pgcopyEntries.entrySet()) {
                putFileEntry(tar, entry.getKey(), entry.getValue());
            }
            tar.finish();
        } catch (IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed assembling tar.gz plaintext: " + e.getMessage(), e);
        } finally {
            for (Path p : pgcopyEntries.values()) {
                deleteQuietly(p);
            }
        }
    }

    private static void putBytesEntry(TarArchiveOutputStream tar, String name, byte[] payload)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(payload.length);
        tar.putArchiveEntry(entry);
        tar.write(payload);
        tar.closeArchiveEntry();
    }

    private static void putFileEntry(TarArchiveOutputStream tar, String name, Path file)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(file.toFile(), name);
        tar.putArchiveEntry(entry);
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(tar);
        }
        tar.closeArchiveEntry();
    }

    private Path temp(String hint) {
        try {
            return Files.createTempFile(workDir, "alpf-" + hint + "-", ".tmp");
        } catch (IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Cannot create temp file in " + workDir + ": " + e.getMessage(), e);
        }
    }

    private static Map<EntityType, Mapper> mappersByType() {
        Map<EntityType, Mapper> byType = new LinkedHashMap<>();
        for (Mapper mapper : KnownMappers.all()) {
            byType.put(mapper.entityType(), mapper);
        }
        return byType;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // Temp file in the OS temp dir — leak rather than fail the export.
        }
    }
}

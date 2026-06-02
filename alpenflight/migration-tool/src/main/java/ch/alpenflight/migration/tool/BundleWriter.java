package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
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
 *   <li>For {@code FULL_PORT} entities (except CLUB, whose id-map is
 *       orchestrator-owned — see {@link EntityType#idMapSeededFromProvisioning()}),
 *       emit {@code legacy_id_map/<E>.pgcopy} — a binary PGCOPY map the consumer
 *       COPYs into {@code legacy_id_map_<entity>} so FK rewrites resolve.
 *       Non-fan-out entities get the 2-column identity map
 *       ({@code legacy_guid -> legacy_guid}); fan-out entities
 *       ({@link EntityType#fansOut()}) get the 3-column composite map
 *       ({@code legacy_guid, club_id, id}) — see
 *       {@link #writeIdentityPgcopy}.</li>
 *   <li>Assemble the final tar (manifest.json entry 0, then the pgcopy
 *       streams, then the entity NDJSON streams) piped through gzip into the
 *       output temp file. The server ingest drains entries single-pass in tar
 *       order and resolves FKs synchronously during each NDJSON's ingest, so
 *       every {@code legacy_id_map/*.pgcopy} must precede the NDJSON streams: a
 *       fan-out child's composite {@code (legacy_guid, club_id)} FK lookup
 *       against {@code legacy_id_map_location} runs while its own NDJSON is
 *       ingested, and that map must already be populated (J-0b T-10).</li>
 * </ol>
 *
 * <p>Tar entry names match the server ingest contract: {@code <E>.ndjson}
 * (mapped via {@code EntityType.valueOf}) and {@code legacy_id_map/<E>.pgcopy}
 * (the {@code COPY legacy_id_map_<E>} target folds to lowercase in Postgres).
 */
public final class BundleWriter {

    // AUTO_CLOSE_TARGET disabled: streamOne creates a JsonGenerator PER ROW
    // wrapping the SAME shared per-entity DigestOutputStream, so the generator's
    // close() at the end of each row's try-with-resources must NOT close the
    // underlying stream. With Jackson's default (auto-close on), the first row's
    // gen.close() closes digestOut -> fileOut -> the NIO file channel; subsequent
    // writes ('\n', the next row's bytes) only surface once the 8KB
    // BufferedOutputStream flushes — i.e. mid-stream, not on row 2 — manifesting
    // as a ClosedChannelException at a buffer-boundary row (J-0c T-13: COUNTRY
    // died at row 115). The stream is owned by streamOne's try-with-resources and
    // closed exactly once there; the per-row generators must only flush.
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .build();
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
        String sql = MapperLegacyBindings.selectForProducer(entity);
        try (ResultSet rs = reader.openEntityCursor(sql)) {
            return drainCursor(entity, mapper, rs);
        } catch (SQLException e) {
            // Cursor open / close failures (driver/socket level) — same surfacing
            // contract as the per-row drain below.
            if (verbose) {
                System.err.println("  streaming " + entity
                        + " failed opening/closing cursor — stack trace follows:");
                e.printStackTrace();
            }
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed streaming entity " + entity + ": " + describe(e), e);
        }
    }

    /**
     * Drain one open forward-only cursor into a per-entity NDJSON temp file,
     * hashing + counting rows. Package-private so a test can drive the
     * multi-fetch-window / buffer-boundary path with a fake {@link ResultSet}
     * (no live MSSQL): the per-row {@link JsonGenerator} writes share one
     * {@link DigestOutputStream} buffered over an NIO file channel, and that
     * stream must survive every row's {@code gen.close()} (J-0c T-13).
     */
    EntityStreamResult drainCursor(EntityType entity, Mapper mapper, ResultSet rs) {
        Path ndjson = temp(entity.name() + "-ndjson");
        long rows = 0;
        MessageDigest digest = newSha256();
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(ndjson));
             DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest)) {
            while (rs.next()) {
                try (JsonGenerator gen = JSON_FACTORY.createGenerator(digestOut)) {
                    mapper.writeNdjson(rs, gen);
                }
                digestOut.write('\n');
                rows++;
            }
        } catch (SQLException | IOException | RuntimeException e) {
            // Surface the real cause: many failures here are NPEs / driver
            // SQLExceptions whose getMessage() is null (a NULL legacy column
            // read as a primitive, a uniqueidentifier coercion, a cursor-level
            // driver fault). e.getMessage() alone collapses those to ": null"
            // and forces an entity-by-entity CI grind to diagnose. Print the
            // exception class + the full cause chain, and dump the stack trace
            // to stderr so the next chain run names the offending row read.
            // RuntimeException is caught alongside the checked types because a
            // mapper NULL-deref (writeRequired*) or UUID.fromString on a NULL
            // legacy GUID is a RuntimeException that would otherwise crash the
            // process with no per-entity context.
            if (verbose) {
                System.err.println("  streaming " + entity + " failed at row "
                        + (rows + 1) + " — stack trace follows:");
                e.printStackTrace();
            }
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed streaming entity " + entity + " at row " + (rows + 1)
                            + ": " + describe(e), e);
        }
        String hex = HexFormat.of().formatHex(digest.digest());
        if (verbose) {
            System.err.printf("  %-26s %8d rows  sha256=%s%n", entity, rows, hex);
        }
        return new EntityStreamResult(entity, ndjson, rows, hex);
    }

    /**
     * Build the {@code legacy_id_map/<E>.pgcopy} for a FULL_PORT entity by
     * re-reading every NDJSON line. Bounded per-entity read (not whole-bundle);
     * the NDJSON temp file already exists.
     *
     * <p>Two shapes, gated on {@link EntityType#fansOut()}:
     * <ul>
     *   <li><strong>Identity (2-column)</strong> — non-fan-out entities: the
     *       legacy GUID maps to itself ({@code legacy_guid -> legacy_guid}), per
     *       ADR 0019 legacy-GUID preservation, so same-entity FK rewrites
     *       resolve.</li>
     *   <li><strong>Composite (3-column)</strong> — fan-out entities (J-0b): one
     *       shared legacy GUID fans out into N {@code club_id}-distinct replica
     *       ids, so the map is {@code (legacy_guid, club_id, id)} — the
     *       per-replica derived id the producer already emitted on the wire. The
     *       ingest {@code legacy_id_map_<entity>} is composite-keyed so a
     *       downstream FK resolves the referencer's OWN club replica.</li>
     * </ul>
     */
    Path writeIdentityPgcopy(EntityStreamResult ndjsonResult) {
        EntityType entity = ndjsonResult.entityType();
        boolean fanOut = entity.fansOut();
        Path pgcopy = temp(entity.name() + "-pgcopy");
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
                UUID legacyGuid = requireUuid(row, "legacy_guid", entity);
                if (fanOut) {
                    UUID clubId = requireUuid(row, "club_id", entity);
                    UUID id = requireUuid(row, "id", entity);
                    writer.write(legacyGuid, clubId, id);
                } else {
                    writer.write(legacyGuid, legacyGuid);
                }
            }
        } catch (IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed writing id map for " + entity + ": " + e.getMessage(), e);
        }
        return pgcopy;
    }

    private static UUID requireUuid(JsonNode row, String field, EntityType entity) {
        JsonNode node = row.get(field);
        if (node == null || !node.isTextual()) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "NDJSON row for " + entity + " has no " + field
                            + "; cannot build id map");
        }
        return UUID.fromString(node.asText());
    }

    /**
     * Assemble manifest + identity pgcopy maps + entity streams into a
     * gzip-compressed tar at {@code destination}. Entry order: manifest.json,
     * then each FULL_PORT entity's pgcopy, then each entity's NDJSON.
     *
     * <p>The pgcopy id-maps MUST precede the NDJSON streams. The server ingest
     * ({@code MigrationBundleIngestService.drainEntityStreams}) is single-pass
     * in tar arrival order: a {@code legacy_id_map/*.pgcopy} populates the
     * (composite) {@code legacy_id_map_<entity>} temp table, an {@code *.ndjson}
     * resolves that entity's FKs synchronously. A fan-out child
     * (INOUTBOUND_POINT) resolves its {@code location_id} FK against
     * {@code legacy_id_map_location} DURING its own NDJSON ingest, so the
     * LOCATION pgcopy must already be drained — NDJSON-first fails closed with
     * {@code BUNDLE_CROSS_TENANT_FK_LEAK} (J-0b T-10). The temp tables are
     * created up front by {@code createTemporaryIdMapTables} and the derived
     * replica ids are producer-computed ({@code Coercions.deriveFanOutId})
     * independent of NDJSON insertion, so populating every id-map before
     * draining any NDJSON is correct (it mirrors the seed-CLUB-map-before-drain
     * pattern).
     */
    public void assembleTarGz(byte[] manifestBytes,
                              List<EntityStreamResult> entityResults,
                              Path destination) {
        // FULL_PORT identity maps, keyed by tar entry name. CLUB is excluded:
        // its legacy_id_map_club is orchestrator-owned (seedClubLegacyIdMap maps
        // legacy guid -> the provisioned t_club.id), so a producer-emitted CLUB
        // identity pgcopy would collide on legacy_id_map_club_pkey AND be
        // semantically wrong — see EntityType.idMapSeededFromProvisioning (J-0c T-01).
        Map<String, Path> pgcopyEntries = new LinkedHashMap<>();
        for (EntityStreamResult result : entityResults) {
            if (MapperLegacyBindings.portPolicy(result.entityType())
                    == MapperLegacyBindings.PortPolicy.FULL_PORT
                    && !result.entityType().idMapSeededFromProvisioning()) {
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
            // pgcopy id-maps BEFORE NDJSON — see method javadoc: the server
            // resolves fan-out FKs against legacy_id_map_<entity> during NDJSON
            // ingest, so every map must be drained first (J-0b T-10).
            for (Map.Entry<String, Path> entry : pgcopyEntries.entrySet()) {
                putFileEntry(tar, entry.getKey(), entry.getValue());
            }
            for (EntityStreamResult result : entityResults) {
                putFileEntry(tar, result.tarEntryName(), result.ndjsonTempFile());
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

    /**
     * Render an exception as {@code class: message} plus its cause chain, so a
     * null-message throwable (NPE, some driver SQLExceptions) still names its
     * type and underlying cause instead of surfacing as a bare {@code null}.
     */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 8) {
            if (depth > 0) {
                sb.append(" <- caused by ");
            }
            sb.append(current.getClass().getName());
            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
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

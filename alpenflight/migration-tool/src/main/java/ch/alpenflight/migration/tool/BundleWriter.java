package ch.alpenflight.migration.tool;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.LegacyIdMapWriter;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.MapperLegacyBindings;
import ch.alpenflight.migration.bundle.SeedReferenceUuids;
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

public final class BundleWriter {

    private static final JsonFactory PER_ROW_GENERATOR_FACTORY_LEAVING_SHARED_STREAM_OPEN =
            JsonFactory.builder()
                    .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                    .build();
    private static final ObjectMapper NDJSON_ROW_PARSER = new ObjectMapper();

    private final LegacyJdbcReader reader;
    private final Path workDir;
    private final boolean verbose;

    public BundleWriter(LegacyJdbcReader reader, Path workDir, boolean verbose) {
        this.reader = reader;
        this.workDir = workDir;
        this.verbose = verbose;
    }

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
            if (verbose) {
                System.err.println("  streaming " + entity
                        + " failed opening/closing cursor — stack trace follows:");
                e.printStackTrace();
            }
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed streaming entity " + entity + ": " + describe(e), e);
        }
    }

    EntityStreamResult drainCursor(EntityType entity, Mapper mapper, ResultSet rs) {
        Path ndjson = temp(entity.name() + "-ndjson");
        long rows = 0;
        MessageDigest digest = newSha256();
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(ndjson));
             DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest)) {
            while (rs.next()) {
                try (JsonGenerator gen =
                             PER_ROW_GENERATOR_FACTORY_LEAVING_SHARED_STREAM_OPEN
                                     .createGenerator(digestOut)) {
                    mapper.writeNdjson(rs, gen);
                }
                digestOut.write('\n');
                rows++;
            }
        } catch (SQLException | IOException | RuntimeException e) {
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
                JsonNode row = NDJSON_ROW_PARSER.readTree(line);
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

    Path writeSystemGlobalSeedPgcopy(EntityStreamResult ndjsonResult) {
        EntityType entity = ndjsonResult.entityType();
        Path pgcopy = temp(entity.name() + "-seed-pgcopy");
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(pgcopy));
             LegacyIdMapWriter writer = new LegacyIdMapWriter(fileOut);
             BufferedReader lines = Files.newBufferedReader(
                     ndjsonResult.ndjsonTempFile(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = NDJSON_ROW_PARSER.readTree(line);
                UUID legacyGuid = requireUuid(row, "legacy_guid", entity);
                UUID seedPk = resolveSeedPk(entity, row);
                writer.write(legacyGuid, seedPk);
            }
        } catch (IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed writing seed id map for " + entity + ": " + e.getMessage(), e);
        }
        return pgcopy;
    }

    Path writeStartTypeEnumSeedPgcopy() {
        Path pgcopy = temp("START_TYPE-seed-pgcopy");
        Map<UUID, UUID> everyLegacyEnumIdToSeedPk =
                ch.alpenflight.migration.bundle.flight.StartTypeMapper.legacyEnumIdToSeedPk();
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(pgcopy));
             LegacyIdMapWriter writer = new LegacyIdMapWriter(fileOut)) {
            for (Map.Entry<UUID, UUID> entry : everyLegacyEnumIdToSeedPk.entrySet()) {
                writer.write(entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed writing START_TYPE enum seed id map: " + e.getMessage(), e);
        }
        return pgcopy;
    }

    private static boolean isPreSeededInV2BaselineAndMustNotBeReIngested(EntityType entity) {
        return MapperLegacyBindings.portPolicy(entity)
                == MapperLegacyBindings.PortPolicy.SYSTEM_GLOBAL;
    }

    private static boolean hasSeedResolver(EntityType entity) {
        return entity == EntityType.COUNTRY
                || entity == EntityType.CLUB_STATE
                || entity == EntityType.LANGUAGE;
    }

    private static UUID resolveSeedPk(EntityType entity, JsonNode row) {
        UUID seedPk;
        switch (entity) {
            case COUNTRY -> seedPk = SeedReferenceUuids.countryByIso2(textOrNull(row, "iso2_code"));
            case CLUB_STATE -> seedPk = SeedReferenceUuids.clubStateByCode(textOrNull(row, "code"));
            case LANGUAGE -> seedPk = SeedReferenceUuids.languageByCode(textOrNull(row, "code"));
            default -> throw new ExportException(ExitCode.IO_ERROR,
                    "No seed-PK resolver wired for SYSTEM_GLOBAL entity " + entity
                            + "; add it before emitting its id-map.");
        }
        if (seedPk == null) {
            throw new ExportException(ExitCode.IO_ERROR,
                    entity + " NDJSON row " + row + " has no matching new-stack seed PK "
                            + "(its natural key is absent from the V2 seed catalogue).");
        }
        return seedPk;
    }

    private static String textOrNull(JsonNode row, String field) {
        JsonNode node = row.get(field);
        return (node == null || node.isNull()) ? null : node.asText();
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

    public void assembleTarGz(byte[] manifestBytes,
                              List<EntityStreamResult> entityResults,
                              Path destination) {
        Map<String, Path> idMapEntriesTarredBeforeNdjsonStreams = new LinkedHashMap<>();
        for (EntityStreamResult result : entityResults) {
            EntityType entity = result.entityType();
            MapperLegacyBindings.PortPolicy policy = MapperLegacyBindings.portPolicy(entity);
            if (policy == MapperLegacyBindings.PortPolicy.FULL_PORT
                    && !entity.idMapSeededFromProvisioning()
                    && entity.emitsIdentityMap()) {
                idMapEntriesTarredBeforeNdjsonStreams.put(
                        "legacy_id_map/" + entity.name() + ".pgcopy",
                        writeIdentityPgcopy(result));
            } else if (entity == EntityType.START_TYPE) {
                idMapEntriesTarredBeforeNdjsonStreams.put(
                        "legacy_id_map/" + entity.name() + ".pgcopy",
                        writeStartTypeEnumSeedPgcopy());
            } else if (policy == MapperLegacyBindings.PortPolicy.SYSTEM_GLOBAL
                    && hasSeedResolver(entity)) {
                idMapEntriesTarredBeforeNdjsonStreams.put(
                        "legacy_id_map/" + entity.name() + ".pgcopy",
                        writeSystemGlobalSeedPgcopy(result));
            }
        }
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(destination));
             GZIPOutputStream gzip = new GZIPOutputStream(fileOut);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            putBytesEntry(tar, "manifest.json", manifestBytes);
            for (Map.Entry<String, Path> entry
                    : idMapEntriesTarredBeforeNdjsonStreams.entrySet()) {
                putFileEntry(tar, entry.getKey(), entry.getValue());
            }
            for (EntityStreamResult result : entityResults) {
                if (isPreSeededInV2BaselineAndMustNotBeReIngested(result.entityType())) {
                    continue;
                }
                putFileEntry(tar, result.tarEntryName(), result.ndjsonTempFile());
            }
            tar.finish();
        } catch (IOException e) {
            throw new ExportException(ExitCode.IO_ERROR,
                    "Failed assembling tar.gz plaintext: " + e.getMessage(), e);
        } finally {
            for (Path p : idMapEntriesTarredBeforeNdjsonStreams.values()) {
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
        }
    }
}

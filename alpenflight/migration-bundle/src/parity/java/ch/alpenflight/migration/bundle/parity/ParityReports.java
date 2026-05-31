package ch.alpenflight.migration.bundle.parity;

import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Emits the parity report tree under {@code build/reports/parity/<run-id>/}:
 *
 * <pre>
 *   summary.json     — per-mapper counts + outcome flags (passed,
 *                      totalDeltas, fkOrphans)
 *   report.md        — human-readable summary mirror
 *   deltas/*.json    — one file per entity with a non-empty delta list,
 *                      keyed by legacy GUID where applicable
 * </pre>
 *
 * <p>{@code summary.json.fkOrphans} is emitted as JSON {@code null} until
 * the FK orphan walker lands at S-187a; downstream tooling distinguishes
 * "measured zero" from "not yet implemented" by keying on the field's
 * concrete value.
 *
 * <p>PII columns are never written. Vertical-slice reports carry only
 * structural metadata (entity name, counts, sentinel column names). When
 * S-187a adds the sampled-value diff, the PII-column allow-list lives
 * alongside the emitter so an additional mapper cannot smuggle a
 * {@code Persons.Firstname} into a delta file.
 */
public final class ParityReports {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ParityReports() { }

    public static Path write(
            Path reportsDirectory,
            ParityRunIdentity runIdentity,
            Map<EntityType, Integer> producerCounts,
            Map<EntityType, Integer> consumerCounts,
            ParityDiffEngine.DiffOutcome diffOutcome,
            @Nullable Integer fkOrphans) throws IOException {
        Files.createDirectories(reportsDirectory);
        Path summaryFile = reportsDirectory.resolve("summary.json");
        writeSummary(summaryFile, runIdentity, producerCounts, consumerCounts, diffOutcome,
                fkOrphans);
        writeReportMarkdown(reportsDirectory.resolve("report.md"), runIdentity,
                producerCounts, consumerCounts, diffOutcome, fkOrphans);
        writeDeltas(reportsDirectory.resolve("deltas"), diffOutcome);
        return summaryFile;
    }

    private static void writeSummary(
            Path summaryFile,
            ParityRunIdentity runIdentity,
            Map<EntityType, Integer> producerCounts,
            Map<EntityType, Integer> consumerCounts,
            ParityDiffEngine.DiffOutcome diffOutcome,
            @Nullable Integer fkOrphans) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("runId", runIdentity.runId());
        root.put("seed", runIdentity.seed());
        root.put("scale", runIdentity.scale());
        root.put("generatedAt", Instant.now().toString());
        root.put("passed", diffOutcome.passed());
        root.put("totalDeltas", diffOutcome.totalDeltas());
        // JSON null until the caller supplies a measured walk result, so a
        // downstream consumer keying on the field cannot misread an unmeasured
        // zero for a verified zero. The walk that supplies a concrete value
        // lands with the round-trip extension (deferred follow-up).
        if (fkOrphans == null) {
            root.putNull("fkOrphans");
        } else {
            root.put("fkOrphans", fkOrphans.intValue());
        }
        ObjectNode perMapper = root.putObject("perMapper");
        for (Map.Entry<EntityType, ParityDiffEngine.MapperSentinels> entry
                : diffOutcome.sentinelsByEntity().entrySet()) {
            ObjectNode mapperNode = perMapper.putObject(entry.getKey().name());
            mapperNode.put("producerRows", producerCounts.getOrDefault(entry.getKey(), 0));
            mapperNode.put("consumerRows", consumerCounts.getOrDefault(entry.getKey(), 0));
            ArrayNode sentinelArray = mapperNode.putArray("sentinelColumns");
            for (String column : new TreeSet<>(entry.getValue().sentinels())) {
                sentinelArray.add(column);
            }
            ArrayNode ignoredArray = mapperNode.putArray("ignoredColumns");
            for (String column : new TreeSet<>(entry.getValue().ignored())) {
                ignoredArray.add(column);
            }
        }
        Files.writeString(summaryFile, JSON.writeValueAsString(root));
    }

    private static void writeReportMarkdown(
            Path reportFile,
            ParityRunIdentity runIdentity,
            Map<EntityType, Integer> producerCounts,
            Map<EntityType, Integer> consumerCounts,
            ParityDiffEngine.DiffOutcome diffOutcome,
            @Nullable Integer fkOrphans) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("# Parity oracle run ").append(runIdentity.runId()).append('\n').append('\n');
        body.append("- Seed: `").append(runIdentity.seed()).append("`\n");
        body.append("- Scale: `").append(runIdentity.scale()).append("`\n");
        body.append("- Outcome: ").append(diffOutcome.passed() ? "PASS" : "FAIL").append('\n');
        body.append("- Row-count deltas: ").append(diffOutcome.totalDeltas()).append('\n');
        body.append("- FK orphans: ")
                .append(fkOrphans == null ? "not measured" : fkOrphans.toString())
                .append('\n');
        body.append('\n').append("## Per-mapper counts").append('\n').append('\n');
        body.append("| Entity | Producer rows | Consumer rows | Sentinels | Ignored |\n");
        body.append("|---|---:|---:|---:|---:|\n");
        for (Map.Entry<EntityType, ParityDiffEngine.MapperSentinels> entry
                : diffOutcome.sentinelsByEntity().entrySet()) {
            body.append("| ").append(entry.getKey()).append(" | ")
                    .append(producerCounts.getOrDefault(entry.getKey(), 0)).append(" | ")
                    .append(consumerCounts.getOrDefault(entry.getKey(), 0)).append(" | ")
                    .append(entry.getValue().sentinels().size()).append(" | ")
                    .append(entry.getValue().ignored().size()).append(" |\n");
        }
        if (!diffOutcome.rowCountDeltas().isEmpty()) {
            body.append('\n').append("## Row-count deltas").append('\n').append('\n');
            body.append("| Entity | Club | Legacy | New |\n").append("|---|---|---:|---:|\n");
            for (ParityDiffEngine.RowCountDelta delta : diffOutcome.rowCountDeltas()) {
                body.append("| ").append(delta.entity()).append(" | ")
                        .append(delta.clubId()).append(" | ")
                        .append(delta.legacyCount()).append(" | ")
                        .append(delta.newCount()).append(" |\n");
            }
        }
        Files.writeString(reportFile, body.toString());
    }

    private static void writeDeltas(
            Path deltasDirectory,
            ParityDiffEngine.DiffOutcome diffOutcome) throws IOException {
        if (diffOutcome.rowCountDeltas().isEmpty()) {
            return;
        }
        Files.createDirectories(deltasDirectory);
        Map<EntityType, List<ParityDiffEngine.RowCountDelta>> grouped =
                new EnumMap<>(EntityType.class);
        for (ParityDiffEngine.RowCountDelta delta : diffOutcome.rowCountDeltas()) {
            grouped.computeIfAbsent(delta.entity(), key -> new ArrayList<>()).add(delta);
        }
        for (Map.Entry<EntityType, List<ParityDiffEngine.RowCountDelta>> entry
                : grouped.entrySet()) {
            Path deltaFile = deltasDirectory.resolve(entry.getKey().name() + ".json");
            ObjectNode root = JSON.createObjectNode();
            root.put("entity", entry.getKey().name());
            ArrayNode deltaArray = root.putArray("rowCountDeltas");
            for (ParityDiffEngine.RowCountDelta delta : entry.getValue()) {
                ObjectNode deltaNode = deltaArray.addObject();
                deltaNode.put("clubId", delta.clubId());
                deltaNode.put("legacyCount", delta.legacyCount());
                deltaNode.put("newCount", delta.newCount());
            }
            Files.writeString(deltaFile, JSON.writeValueAsString(root));
        }
    }
}

package ch.alpenflight.legacyextract.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TenantClassifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    private static final Set<TenantScope> KIND_BUCKETS = Set.of(
            TenantScope.REFERENCE_DATA,
            TenantScope.PRINCIPAL_SUBJECT,
            TenantScope.CROSS_TENANT,
            TenantScope.SYSTEM_GLOBAL);

    private TenantClassifier() {}

    public static List<TenantClassificationRecord> classify(
            JsonNode tablesJson,
            JsonNode columnsJson,
            JsonNode fksJson,
            JsonNode rulesYaml) {

        Map<String, Set<String>> columnsPerTable = buildColumnIndex(columnsJson);
        Map<String, Set<String>> fkTargets = buildFkTargetIndex(fksJson);
        JsonNode overrides = rulesYaml != null ? rulesYaml.path("overrides") : null;

        Set<String> tenantScoped = new java.util.HashSet<>();
        for (JsonNode t : tablesJson) {
            String name = t.get("table_name").asText();
            if (hasClubIdColumn(columnsPerTable.get(name)) || hasOwnerClubIdColumn(columnsPerTable.get(name))) {
                tenantScoped.add(name);
            }
        }
        if (overrides != null && overrides.isObject()) {
            overrides.fields().forEachRemaining(e -> {
                if ("tenant-scoped".equals(e.getValue().path("kind").asText())) {
                    tenantScoped.add(e.getKey());
                }
            });
        }

        List<TenantClassificationRecord> out = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        var sortedTableNames = new java.util.TreeSet<String>();
        tablesJson.forEach(n -> sortedTableNames.add(n.get("table_name").asText()));

        for (String name : sortedTableNames) {
            JsonNode override = overrides != null ? overrides.path(name) : null;
            TenantScope legacyScope;
            try {
                legacyScope = computeLegacyScope(name, columnsPerTable, fkTargets, tenantScoped, override);
            } catch (IllegalStateException noClassificationRuleMatched) {
                unclassified.add(name);
                continue;
            }
            TenantScope targetScope = override != null && override.hasNonNull("target_scope")
                    ? TenantScope.valueOf(override.get("target_scope").asText())
                    : legacyScope;

            String targetEntity = override != null && override.hasNonNull("target_entity")
                    ? override.get("target_entity").asText()
                    : singularizeLegacyTableName(name);

            String rationaleRef = override != null ? override.path("rationale_ref").asText(null) : null;
            String tenantColumn = targetScope == TenantScope.TENANT_SCOPED ? "club_id" : null;

            String via = null;
            if (legacyScope == TenantScope.INDIRECT_TENANT) {
                via = resolveIndirectPath(name, fksJson, tenantScoped);
            }

            List<String> preconditions = toStringList(override, "preconditions");
            List<String> rideThroughs = toStringList(override, "ride_through_targets");

            boolean piiBlob = override != null && override.path("pii_blob").asBoolean(false);
            boolean emitsAudit = override != null
                    ? override.path("emits_audit").asBoolean(defaultEmitsAudit(targetScope))
                    : defaultEmitsAudit(targetScope);

            String enforcement = "hibernate_only";

            out.add(new TenantClassificationRecord(
                    name,
                    targetEntity,
                    legacyScope,
                    targetScope,
                    tenantColumn,
                    rationaleRef,
                    via,
                    preconditions,
                    piiBlob,
                    emitsAudit,
                    rideThroughs,
                    enforcement));
        }
        if (!unclassified.isEmpty()) {
            throw new IllegalStateException(
                    "tenant-rules.yaml: " + unclassified.size()
                            + " unclassified tables — add `kind:` overrides or document the FK chain: "
                            + unclassified);
        }
        return out;
    }

    public static JsonNode loadRules(Path yamlPath) throws IOException {
        if (yamlPath == null || !Files.exists(yamlPath)) {
            return null;
        }
        return YAML.readTree(yamlPath.toFile());
    }

    private static TenantScope computeLegacyScope(
            String name,
            Map<String, Set<String>> columnsPerTable,
            Map<String, Set<String>> fkTargets,
            Set<String> tenantScoped,
            JsonNode override) {

        if (override != null && override.hasNonNull("kind")) {
            return switch (override.get("kind").asText()) {
                case "reference" -> TenantScope.REFERENCE_DATA;
                case "principal" -> TenantScope.PRINCIPAL_SUBJECT;
                case "cross-tenant" -> TenantScope.CROSS_TENANT;
                case "system" -> TenantScope.SYSTEM_GLOBAL;
                case "tenant-scoped" -> TenantScope.TENANT_SCOPED;
                default -> throw new IllegalStateException(
                        "tenant-rules.yaml: unknown 'kind' value '"
                                + override.get("kind").asText() + "' for " + name);
            };
        }

        Set<String> columns = columnsPerTable.get(name);
        if (hasClubIdColumn(columns) || hasOwnerClubIdColumn(columns)) {
            return TenantScope.TENANT_SCOPED;
        }

        for (String fkTarget : fkTargets.getOrDefault(name, Set.of())) {
            if (tenantScoped.contains(fkTarget)) {
                return TenantScope.INDIRECT_TENANT;
            }
        }

        throw new IllegalStateException(
                "tenant-rules.yaml: no override for table '" + name
                        + "', no ClubId/OwnerClubId column, no FK to a tenant-scoped table. "
                        + "Add a `kind:` override in tenant-rules.yaml or document the FK chain.");
    }

    private static boolean defaultEmitsAudit(TenantScope scope) {
        return scope == TenantScope.TENANT_SCOPED || scope == TenantScope.INDIRECT_TENANT;
    }

    private static String singularizeLegacyTableName(String legacyTable) {
        if (legacyTable.endsWith("ies") && legacyTable.length() > 3) {
            return legacyTable.substring(0, legacyTable.length() - 3) + "y";
        }
        if (legacyTable.endsWith("es") && legacyTable.length() > 2) {
            char characterPrecedingTheEsSuffix = legacyTable.charAt(legacyTable.length() - 3);
            if (characterPrecedingTheEsSuffix == 's'
                    || characterPrecedingTheEsSuffix == 'x'
                    || characterPrecedingTheEsSuffix == 'z'
                    || characterPrecedingTheEsSuffix == 'h') {
                return legacyTable.substring(0, legacyTable.length() - 2);
            }
        }
        if (legacyTable.endsWith("s") && !legacyTable.endsWith("ss")) {
            return legacyTable.substring(0, legacyTable.length() - 1);
        }
        return legacyTable;
    }

    private static boolean hasClubIdColumn(Set<String> columns) {
        if (columns == null) return false;
        for (String c : columns) {
            if (c.equalsIgnoreCase("ClubId")) return true;
        }
        return false;
    }

    private static boolean hasOwnerClubIdColumn(Set<String> columns) {
        if (columns == null) return false;
        for (String c : columns) {
            if (c.equalsIgnoreCase("OwnerClubId")) return true;
        }
        return false;
    }

    private static Map<String, Set<String>> buildColumnIndex(JsonNode columnsJson) {
        Map<String, Set<String>> columnNamesPerTable = new LinkedHashMap<>();
        for (JsonNode column : columnsJson) {
            String table = column.get("table_name").asText();
            String columnName = column.get("column_name").asText();
            columnNamesPerTable.computeIfAbsent(table, k -> new java.util.HashSet<>()).add(columnName);
        }
        return columnNamesPerTable;
    }

    private static Map<String, Set<String>> buildFkTargetIndex(JsonNode fksJson) {
        Map<String, Set<String>> fkTargetsPerTable = new LinkedHashMap<>();
        for (JsonNode fk : fksJson) {
            String source = fk.get("table").asText();
            String target = fk.get("referenced_table").asText();
            fkTargetsPerTable.computeIfAbsent(source, k -> new java.util.HashSet<>()).add(target);
        }
        return fkTargetsPerTable;
    }

    private static String resolveIndirectPath(String table, JsonNode fksJson, Set<String> tenantScoped) {
        for (JsonNode fk : fksJson) {
            if (!table.equals(fk.get("table").asText())) continue;
            String target = fk.get("referenced_table").asText();
            if (!tenantScoped.contains(target)) continue;
            String column = fk.get("columns").size() > 0 ? fk.get("columns").get(0).asText() : "?";
            String targetColumn = fk.get("referenced_columns").size() > 0
                    ? fk.get("referenced_columns").get(0).asText()
                    : "?";
            return column + " -> " + target + "." + targetColumn;
        }
        return null;
    }

    private static List<String> toStringList(JsonNode override, String key) {
        if (override == null) return List.of();
        JsonNode node = override.path(key);
        if (!node.isArray()) return List.of();
        List<String> out = new ArrayList<>(node.size());
        node.forEach(n -> out.add(n.asText()));
        return out;
    }
}

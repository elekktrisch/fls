package ch.alpenflight.multitenancy.leakage;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.TenantScopedEntityCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * S-024's native-SQL escape-hatch gate. Pure JUnit — no Spring, no Docker.
 * Two responsibilities:
 *
 * <ol>
 *   <li><strong>Caller scan.</strong> Text-grep
 *       {@code alpenflight/server/src/main/java/**} for native-SQL call
 *       sites ({@code @Query(nativeQuery = true)},
 *       {@code createNativeQuery(}, {@code JdbcTemplate.*query},
 *       {@code NamedParameterJdbcTemplate.*query}). For each hit, scan the
 *       enclosing string literals for any tenant-scoped table name
 *       (derived from {@link TenantScopedEntityCatalog#resolveTableName}).
 *       A hit against a tenant-scoped table that is not listed in
 *       {@code native-sql-register.md}'s allow-list fails the build.</li>
 *   <li><strong>Register hygiene.</strong> Every entry in the register has
 *       a parseable {@code Expires: YYYY-MM-DD}; entries past today's date
 *       fail the build (not warn — a CI warning gets scrolled past, and
 *       renewal is a 1-line PR).</li>
 * </ol>
 *
 * <p>Today the register is empty: any native SQL hit fails immediately.
 * Promote to a JavaParser-based AST if comment / log-string false-positives
 * appear in the future — the text-grep is intentionally conservative.
 */
class NativeSqlRegisterTest {

    private static final Pattern NATIVE_CALL_PATTERN = Pattern.compile(
            "nativeQuery\\s*=\\s*true|createNativeQuery\\s*\\(|"
                    + "JdbcTemplate\\b|NamedParameterJdbcTemplate\\b");

    private static final Pattern REGISTER_ENTRY_HEADER = Pattern.compile("^###\\s+`([^`]+)`");
    // Field-name group allows hyphens — needed for "Tenant-scoped tables touched"
    // (the field name documented in the template + parser switch lower-case key).
    // Boyscout fix to S-024's pattern.
    private static final Pattern REGISTER_FIELD = Pattern.compile(
            "^-\\s+\\*\\*([A-Za-z][A-Za-z\\- ]*?)(?:\\:)?\\*\\*\\s*:?\\s*(.*?)$");
    /** Java string literal OR text block; group(1) holds the body in either form. */
    private static final Pattern STRING_LITERAL = Pattern.compile(
            "\"\"\"([\\s\\S]*?)\"\"\"|\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");

    @Test
    void no_native_sql_hits_tenant_scoped_table_outside_register() throws IOException {
        Set<String> tenantScopedTables = tenantScopedTableNames();
        Map<String, Set<String>> register = parseRegisterAsCallerToTables();
        Path srcMain = locateServerSrcMain();

        List<String> violations = new ArrayList<>();
        for (Path javaFile : collectMainJavaFiles(srcMain)) {
            String body = Files.readString(javaFile, StandardCharsets.UTF_8);
            if (!NATIVE_CALL_PATTERN.matcher(body).find()) {
                continue;
            }
            String relPath = relativize(srcMain, javaFile);
            Matcher lit = STRING_LITERAL.matcher(body);
            while (lit.find()) {
                String literal = lit.group(1) != null ? lit.group(1) : lit.group(2);
                String sql = literal.toLowerCase(Locale.ROOT);
                for (String table : tenantScopedTables) {
                    if (!containsTableReference(sql, table)) {
                        continue;
                    }
                    Set<String> approvedTables = register.get(relPath);
                    if (approvedTables == null || !approvedTables.contains(table)) {
                        violations.add(relPath + " → " + table);
                    }
                }
            }
        }
        assertThat(violations)
                .as("Native-SQL call site hitting a tenant-scoped table without a "
                        + "native-sql-register.md entry — register the call site or "
                        + "rewrite via JPA")
                .isEmpty();
    }

    @Test
    void no_register_entries_past_expires_date() throws IOException {
        List<RegisterEntry> entries = parseRegisterEntries();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        List<String> expired = entries.stream()
                .filter(e -> e.expires != null && e.expires.isBefore(today))
                .map(e -> e.id + " expired " + e.expires)
                .toList();
        assertThat(expired)
                .as("Expired native-SQL register entries — renew or remove the call site")
                .isEmpty();
    }

    private static Set<String> tenantScopedTableNames() {
        Set<String> out = new HashSet<>();
        for (Class<?> c : TenantScopedEntityCatalog.discoverTenantScopedEntities()) {
            out.add(TenantScopedEntityCatalog.resolveTableName(c));
        }
        return out;
    }

    private static List<Path> collectMainJavaFiles(Path srcMainRoot) throws IOException {
        try (var stream = Files.walk(srcMainRoot)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * The whole word check would catch the table name appearing as a column
     * or alias; require an adjacent SQL keyword (FROM / JOIN / INTO / UPDATE /
     * DELETE FROM) to narrow to real table references.
     */
    private static boolean containsTableReference(String sql, String tableLowercase) {
        Pattern p = Pattern.compile(
                "\\b(from|join|into|update|delete\\s+from)\\s+\""
                        + Pattern.quote(tableLowercase) + "\"|"
                        + "\\b(from|join|into|update|delete\\s+from)\\s+"
                        + Pattern.quote(tableLowercase) + "\\b");
        return p.matcher(sql).find();
    }

    /**
     * Returns relPath → set-of-tenant-scoped-tables-approved-for-that-file.
     * A single register entry may list multiple tables; each is allow-listed
     * for the entry's caller path.
     */
    private static Map<String, Set<String>> parseRegisterAsCallerToTables() throws IOException {
        Map<String, Set<String>> out = new java.util.LinkedHashMap<>();
        for (RegisterEntry entry : parseRegisterEntries()) {
            int colon = entry.caller.indexOf(':');
            String path = colon > 0 ? entry.caller.substring(0, colon) : entry.caller;
            path = normalizeRelativePath(path);
            Set<String> tables = out.computeIfAbsent(path, k -> new HashSet<>());
            for (String t : entry.tables.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) {
                    tables.add(trimmed);
                }
            }
        }
        return out;
    }

    private static List<RegisterEntry> parseRegisterEntries() throws IOException {
        Path register = locateNativeSqlRegister();
        List<RegisterEntry> entries = new ArrayList<>();
        if (!Files.exists(register)) {
            return entries;
        }
        String body = Files.readString(register, StandardCharsets.UTF_8);
        RegisterEntry current = null;
        for (String line : body.split("\\R")) {
            Matcher header = REGISTER_ENTRY_HEADER.matcher(line);
            if (header.find()) {
                if (current != null) {
                    entries.add(current);
                }
                current = new RegisterEntry();
                current.id = header.group(1);
                continue;
            }
            if (current == null) continue;
            Matcher field = REGISTER_FIELD.matcher(line);
            if (!field.find()) continue;
            String key = field.group(1).trim().toLowerCase(Locale.ROOT);
            String value = stripBackticks(field.group(2).trim());
            switch (key) {
                case "caller" -> current.caller = value;
                case "tenant-scoped tables touched" -> current.tables = value;
                case "expires" -> current.expires = parseExpiryDate(value);
                default -> { /* ignored fields */ }
            }
        }
        if (current != null) {
            entries.add(current);
        }
        return entries;
    }

    private static LocalDate parseExpiryDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            // tolerate "YYYY-MM-DD (12 months from Approved by default)" comment
            int space = value.indexOf(' ');
            String head = space > 0 ? value.substring(0, space) : value;
            try {
                return LocalDate.parse(head);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** Drop a leading `./`, normalize Windows separators. */
    private static String normalizeRelativePath(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        // Strip a leading `alpenflight/server/` if the register entry includes it
        // — both forms refer to the same file.
        if (normalized.startsWith("alpenflight/server/")) {
            normalized = normalized.substring("alpenflight/server/".length());
        }
        return normalized;
    }

    /** Project-relative path from server/ — matches the form a register entry uses. */
    private static String relativize(Path srcMainRoot, Path javaFile) {
        // srcMainRoot is `<repo>/alpenflight/server/src/main/java`; relativize
        // against server/ so the key is `src/main/java/...` — what a contributor
        // copies from their IDE's file tree.
        Path serverRoot = srcMainRoot.getParent().getParent().getParent();
        return serverRoot.relativize(javaFile).toString().replace('\\', '/');
    }

    private static String stripBackticks(String value) {
        if (value.startsWith("`") && value.endsWith("`") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Path locateServerSrcMain() {
        Path cwd = Path.of("").toAbsolutePath();
        Path probe = cwd;
        while (probe != null) {
            Path candidate = probe.resolve("alpenflight/server/src/main/java");
            if (Files.exists(candidate)) return candidate;
            Path siblingCandidate = probe.resolve("src/main/java");
            if (Files.exists(siblingCandidate)) return siblingCandidate;
            probe = probe.getParent();
        }
        throw new IllegalStateException("server src/main/java not found from " + cwd);
    }

    private static Path locateNativeSqlRegister() {
        Path cwd = Path.of("").toAbsolutePath();
        Path probe = cwd;
        while (probe != null) {
            Path candidate = probe.resolve("alpenflight/database/native-sql-register.md");
            if (Files.exists(candidate)) return candidate;
            Path siblingCandidate = probe.resolve("../database/native-sql-register.md").normalize();
            if (Files.exists(siblingCandidate)) return siblingCandidate;
            probe = probe.getParent();
        }
        return cwd.resolve("alpenflight/database/native-sql-register.md");
    }

    private static final class RegisterEntry {
        String id = "";
        String caller = "";
        String tables = "";
        LocalDate expires;
    }
}

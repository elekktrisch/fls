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
                    + "JdbcTemplate\\b.*?\\b(query|update|execute)|"
                    + "NamedParameterJdbcTemplate\\b.*?\\b(query|update|execute)");

    private static final Pattern REGISTER_ENTRY_HEADER = Pattern.compile("^###\\s+`([^`]+)`");
    private static final Pattern REGISTER_FIELD = Pattern.compile(
            "^-\\s+\\*\\*([A-Za-z][A-Za-z ]*?)(?:\\:)?\\*\\*\\s*:?\\s*(.*?)$");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");

    @Test
    void no_native_sql_hits_tenant_scoped_table_outside_register() throws IOException {
        Set<String> tenantScopedTables = tenantScopedTableNames();
        Map<String, RegisterEntry> register = parseRegister();

        List<String> violations = new ArrayList<>();
        for (Path javaFile : collectMainJavaFiles()) {
            String body = Files.readString(javaFile, StandardCharsets.UTF_8);
            if (!NATIVE_CALL_PATTERN.matcher(body).find()) {
                continue;
            }
            // Native-SQL primitive used in this file — scan string literals
            // for tenant-scoped table names.
            Matcher lit = STRING_LITERAL.matcher(body);
            while (lit.find()) {
                String sql = lit.group(1).toLowerCase(Locale.ROOT);
                for (String table : tenantScopedTables) {
                    if (containsWholeWord(sql, table.toLowerCase(Locale.ROOT))) {
                        String relPath = javaFile.toString().replace('\\', '/');
                        String key = relPath + " → " + table;
                        if (!register.containsKey(key)) {
                            violations.add(key);
                        }
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
        Map<String, RegisterEntry> register = parseRegister();
        LocalDate today = LocalDate.now();
        List<String> expired = register.values().stream()
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

    private static List<Path> collectMainJavaFiles() throws IOException {
        Path root = locateServerSrcMain();
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static Map<String, RegisterEntry> parseRegister() throws IOException {
        Path register = locateNativeSqlRegister();
        Map<String, RegisterEntry> entries = new java.util.LinkedHashMap<>();
        if (!Files.exists(register)) {
            return entries;
        }
        String body = Files.readString(register, StandardCharsets.UTF_8);
        RegisterEntry current = null;
        for (String line : body.split("\n")) {
            Matcher header = REGISTER_ENTRY_HEADER.matcher(line);
            if (header.find()) {
                if (current != null) {
                    entries.put(current.callerKey(), current);
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
                case "expires" -> {
                    try {
                        current.expires = LocalDate.parse(value);
                    } catch (DateTimeParseException ex) {
                        // tolerate "YYYY-MM-DD (12 months from Approved by default)" comment
                        String first = value.split("\\s+")[0];
                        try {
                            current.expires = LocalDate.parse(first);
                        } catch (DateTimeParseException ignored) {
                            current.expires = null;
                        }
                    }
                }
                default -> { /* ignored fields */ }
            }
        }
        if (current != null) {
            entries.put(current.callerKey(), current);
        }
        return entries;
    }

    private static boolean containsWholeWord(String haystack, String word) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(word) + "\\b");
        return p.matcher(haystack).find();
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

        /** Stable key used to look up the entry from a (file, table) violation. */
        String callerKey() {
            // Caller format: "path:line" — keep just the path part for the lookup.
            int colon = caller.indexOf(':');
            String path = colon > 0 ? caller.substring(0, colon) : caller;
            String firstTable = tables.split(",")[0].trim();
            return path + " → " + firstTable;
        }
    }
}

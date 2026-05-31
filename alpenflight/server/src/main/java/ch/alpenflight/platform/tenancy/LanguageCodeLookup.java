package ch.alpenflight.platform.tenancy;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Maps a JWT {@code locale} claim (BCP-47, case-insensitive) onto the
 * canonical {@code language.id} row. Used by the JIT first-login filter
 * to fill the {@code User.languageId} column.
 *
 * <p>The language seed (V2) is static at runtime — eight rows, never
 * mutated after migration. A per-JVM cache populated lazily on first hit
 * keeps the lookup off the SQL path for steady state. Unknown / missing
 * locale resolves to {@code en} ({@link #FALLBACK_EN_ID}) — matches the
 * Keycloak default and the federated-IdP common case.
 *
 * <p>JDBC, not JPA: stays on the same aisle as {@link UserPrincipalLookup}
 * so the resolver runs in Hibernate's session-open path without
 * recursion. Cross-tenant table; no {@code @TenantId} to honor.
 */
@Component
public class LanguageCodeLookup {

    public static final UUID FALLBACK_EN_ID =
            UUID.fromString("019e2e15-2c00-77d3-8000-0000000007d3");

    private static final String SELECT_BY_CODE =
            "SELECT id FROM t_language WHERE lower(code) = ?";

    private final JdbcTemplate jdbc;
    private final ConcurrentMap<String, UUID> cache = new ConcurrentHashMap<>();

    public LanguageCodeLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resolve {@code locale} (BCP-47, e.g. {@code en}, {@code de-CH}) to a
     * {@code language.id}. Lookup is case-insensitive; unknown / null /
     * blank locale resolves to {@link #FALLBACK_EN_ID}.
     */
    public UUID resolve(@Nullable String locale) {
        if (locale == null || locale.isBlank()) {
            return FALLBACK_EN_ID;
        }
        String key = locale.toLowerCase(Locale.ROOT);
        UUID cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        return queryFor(key).map(uuid -> {
            cache.put(key, uuid);
            return uuid;
        }).orElse(FALLBACK_EN_ID);
    }

    private Optional<UUID> queryFor(String lowerCode) {
        try {
            UUID id = jdbc.queryForObject(SELECT_BY_CODE, UUID.class, lowerCode);
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
